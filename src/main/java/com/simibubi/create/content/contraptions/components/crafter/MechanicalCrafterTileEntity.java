package com.simibubi.create.content.contraptions.components.crafter;

import static com.simibubi.create.content.contraptions.base.HorizontalKineticBlock.HORIZONTAL_FACING;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.lang3.tuple.Pair;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.base.KineticTileEntity;
import com.simibubi.create.content.contraptions.components.crafter.ConnectedInputHandler.ConnectedInput;
import com.simibubi.create.content.contraptions.components.crafter.RecipeGridHandler.GroupedItems;
import com.simibubi.create.foundation.item.SmartInventory;
import com.simibubi.create.foundation.tileEntity.TileEntityBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.belt.DirectBeltInputBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.edgeInteraction.EdgeInteractionBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.inventory.InvManipulationBehaviour;
import com.simibubi.create.foundation.utility.BlockFace;
import com.simibubi.create.foundation.utility.Pointing;
import com.simibubi.create.foundation.utility.VecHelper;

import net.minecraft.block.BlockState;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.particles.ItemParticleData;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

public class MechanicalCrafterTileEntity extends KineticTileEntity {

	enum Phase {
		IDLE, ACCEPTING, ASSEMBLING, EXPORTING, WAITING, CRAFTING, INSERTING;
	}

	public static class Inventory extends SmartInventory {

		private MechanicalCrafterTileEntity te;

		public Inventory(MechanicalCrafterTileEntity te) {
			super(1, te, 1, false);
			this.te = te;
			forbidExtraction();
			whenContentsChanged(slot -> {
				if (getStackInSlot(slot).isEmpty())
					return;
				if (te.phase == Phase.IDLE)
					te.checkCompletedRecipe(false);
			});
		}

		@Override
		public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
			if (te.phase != Phase.IDLE)
				return stack;
			if (te.covered)
				return stack;
			return super.insertItem(slot, stack, simulate);
		}

	}

	protected Inventory inventory;
	protected GroupedItems groupedItems = new GroupedItems();
	protected ConnectedInput input = new ConnectedInput();
	protected LazyOptional<IItemHandler> invSupplier = LazyOptional.of(() -> input.getItemHandler(world, pos));
	protected boolean reRender;
	protected Phase phase;
	protected int countDown;
	protected boolean covered;
	protected boolean wasPoweredBefore;

	protected GroupedItems groupedItemsBeforeCraft; // for rendering on client
	private InvManipulationBehaviour inserting;
	private EdgeInteractionBehaviour connectivity;

	private ItemStack scriptedResult = ItemStack.EMPTY;

	public MechanicalCrafterTileEntity(TileEntityType<? extends MechanicalCrafterTileEntity> type) {
		super(type);
		setLazyTickRate(20);
		phase = Phase.IDLE;
		groupedItemsBeforeCraft = new GroupedItems();
		inventory = new Inventory(this);

		// Does not get serialized due to active checking in tick
		wasPoweredBefore = true;
	}

	@Override
	public void addBehaviours(List<TileEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		inserting = new InvManipulationBehaviour(this, this::getTargetFace);
		connectivity = new EdgeInteractionBehaviour(this, ConnectedInputHandler::toggleConnection)
			.connectivity(ConnectedInputHandler::shouldConnect)
			.require(AllItems.WRENCH.get());
		behaviours.add(inserting);
		behaviours.add(connectivity);
	}

	public void blockChanged() {
		removeBehaviour(InvManipulationBehaviour.TYPE);
		inserting = new InvManipulationBehaviour(this, this::getTargetFace);
		attachBehaviourLate(inserting);
	}

	public BlockFace getTargetFace(World world, BlockPos pos, BlockState state) {
		return new BlockFace(pos, MechanicalCrafterBlock.getTargetDirection(state));
	}

	public Direction getTargetDirection() {
		return MechanicalCrafterBlock.getTargetDirection(getBlockState());
	}

	@Override
	public void write(CompoundNBT compound, boolean clientPacket) {
		compound.put("Inventory", inventory.serializeNBT());

		CompoundNBT inputNBT = new CompoundNBT();
		input.write(inputNBT);
		compound.put("ConnectedInput", inputNBT);

		CompoundNBT groupedItemsNBT = new CompoundNBT();
		groupedItems.write(groupedItemsNBT);
		compound.put("GroupedItems", groupedItemsNBT);

		compound.putString("Phase", phase.name());
		compound.putInt("CountDown", countDown);
		compound.putBoolean("Cover", covered);

		super.write(compound, clientPacket);

		if (clientPacket && reRender) {
			compound.putBoolean("Redraw", true);
			reRender = false;
		}
	}

	@Override
	protected void fromTag(BlockState state, CompoundNBT compound, boolean clientPacket) {
		Phase phaseBefore = phase;
		GroupedItems before = this.groupedItems;

		inventory.deserializeNBT(compound.getCompound("Inventory"));
		input.read(compound.getCompound("ConnectedInput"));
		groupedItems = GroupedItems.read(compound.getCompound("GroupedItems"));
		phase = Phase.IDLE;
		String name = compound.getString("Phase");
		for (Phase phase : Phase.values())
			if (phase.name()
				.equals(name))
				this.phase = phase;
		countDown = compound.getInt("CountDown");
		covered = compound.getBoolean("Cover");
		super.fromTag(state, compound, clientPacket);
		if (!clientPacket)
			return;
		if (compound.contains("Redraw"))
			world.notifyBlockUpdate(getPos(), getBlockState(), getBlockState(), 16);
		if (phaseBefore != phase && phase == Phase.CRAFTING)
			groupedItemsBeforeCraft = before;
		if (phaseBefore == Phase.EXPORTING && phase == Phase.WAITING) {
			Direction facing = getBlockState().get(MechanicalCrafterBlock.HORIZONTAL_FACING);
			Vector3d vec = Vector3d.of(facing.getDirectionVec()).scale(.75)
				.add(VecHelper.getCenterOf(pos));
			Direction targetDirection = MechanicalCrafterBlock.getTargetDirection(getBlockState());
			vec = vec.add(Vector3d.of(targetDirection.getDirectionVec()).scale(1));
			world.addParticle(ParticleTypes.CRIT, vec.x, vec.y, vec.z, 0, 0, 0);
		}
	}

	@Override
	public void remove() {
		invSupplier.invalidate();
		super.remove();
	}

	public int getCountDownSpeed() {
		if (getSpeed() == 0)
			return 0;
		return MathHelper.clamp((int) Math.abs(getSpeed()), 4, 250);
	}

	@Override
	public void tick() {
		super.tick();

		if (phase == Phase.ACCEPTING)
			return;

		boolean onClient = world.isRemote;
		boolean runLogic = !onClient || isVirtual();

		if (wasPoweredBefore != world.isBlockPowered(pos)) {
			wasPoweredBefore = world.isBlockPowered(pos);
			if (wasPoweredBefore) {
				if (!runLogic)
					return;
				checkCompletedRecipe(true);
			}
		}

		if (phase == Phase.ASSEMBLING) {
			countDown -= getCountDownSpeed();
			if (countDown < 0) {
				countDown = 0;
				if (!runLogic)
					return;
				if (RecipeGridHandler.getTargetingCrafter(this) != null) {
					phase = Phase.EXPORTING;
					countDown = 1000;
					sendData();
					return;
				}

				ItemStack result =
					isVirtual() ? scriptedResult : RecipeGridHandler.tryToApplyRecipe(world, groupedItems);

				if (result != null) {
					List<ItemStack> containers = new ArrayList<>();
					groupedItems.grid.values()
						.forEach(stack -> {
							if (stack.hasContainerItem())
								containers.add(stack.getContainerItem()
									.copy());
						});

					if (isVirtual())
						groupedItemsBeforeCraft = groupedItems;

					groupedItems = new GroupedItems(result);
					for (int i = 0; i < containers.size(); i++) {
						ItemStack stack = containers.get(i);
						GroupedItems container = new GroupedItems();
						container.grid.put(Pair.of(i, 0), stack);
						container.mergeOnto(groupedItems, Pointing.LEFT);
					}

					phase = Phase.CRAFTING;
					countDown = 2000;
					sendData();
					return;
				}
				ejectWholeGrid();
				return;
			}
		}

		if (phase == Phase.EXPORTING) {
			countDown -= getCountDownSpeed();

			if (countDown < 0) {
				countDown = 0;
				if (!runLogic)
					return;

				MechanicalCrafterTileEntity targetingCrafter = RecipeGridHandler.getTargetingCrafter(this);
				if (targetingCrafter == null) {
					ejectWholeGrid();
					return;
				}

				Pointing pointing = getBlockState().get(MechanicalCrafterBlock.POINTING);
				groupedItems.mergeOnto(targetingCrafter.groupedItems, pointing);
				groupedItems = new GroupedItems();
				phase = Phase.WAITING;
				countDown = 0;
				sendData();
				targetingCrafter.continueIfAllPrecedingFinished();
				targetingCrafter.sendData();
				return;
			}
		}

		if (phase == Phase.CRAFTING) {

			if (onClient) {
				Direction facing = getBlockState().get(MechanicalCrafterBlock.HORIZONTAL_FACING);
				float progress = countDown / 2000f;
				Vector3d facingVec = Vector3d.of(facing.getDirectionVec());
				Vector3d vec = facingVec.scale(.65)
					.add(VecHelper.getCenterOf(pos));
				Vector3d offset = VecHelper.offsetRandomly(Vector3d.ZERO, world.rand, .125f)
					.mul(VecHelper.axisAlingedPlaneOf(facingVec))
					.normalize()
					.scale(progress * .5f)
					.add(vec);
				if (progress > .5f)
					world.addParticle(ParticleTypes.CRIT, offset.x, offset.y, offset.z, 0, 0, 0);

				if (!groupedItemsBeforeCraft.grid.isEmpty() && progress < .5f) {
					if (groupedItems.grid.containsKey(Pair.of(0, 0))) {
						ItemStack stack = groupedItems.grid.get(Pair.of(0, 0));
						groupedItemsBeforeCraft = new GroupedItems();

						for (int i = 0; i < 10; i++) {
							Vector3d randVec = VecHelper.offsetRandomly(Vector3d.ZERO, world.rand, .125f)
								.mul(VecHelper.axisAlingedPlaneOf(facingVec))
								.normalize()
								.scale(.25f);
							Vector3d offset2 = randVec.add(vec);
							randVec = randVec.scale(.35f);
							world.addParticle(new ItemParticleData(ParticleTypes.ITEM, stack), offset2.x, offset2.y,
								offset2.z, randVec.x, randVec.y, randVec.z);
						}
					}
				}
			}

			countDown -= getCountDownSpeed();
			if (countDown < 0) {
				countDown = 0;
				if (!runLogic)
					return;
				tryInsert();
				return;
			}
		}

		if (phase == Phase.INSERTING) {
			if (runLogic && isTargetingBelt())
				tryInsert();
			return;
		}
	}

	protected boolean isTargetingBelt() {
		DirectBeltInputBehaviour behaviour = getTargetingBelt();
		return behaviour != null && behaviour.canInsertFromSide(getTargetDirection());
	}

	protected DirectBeltInputBehaviour getTargetingBelt() {
		BlockPos targetPos = pos.offset(getTargetDirection());
		return TileEntityBehaviour.get(world, targetPos, DirectBeltInputBehaviour.TYPE);
	}

	public void tryInsert() {
		if (!inserting.hasInventory() && !isTargetingBelt()) {
			ejectWholeGrid();
			return;
		}

		boolean chagedPhase = phase != Phase.INSERTING;
		final List<Pair<Integer, Integer>> inserted = new LinkedList<>();

		DirectBeltInputBehaviour behaviour = getTargetingBelt();
		for (Entry<Pair<Integer, Integer>, ItemStack> entry : groupedItems.grid.entrySet()) {
			Pair<Integer, Integer> pair = entry.getKey();
			ItemStack stack = entry.getValue();
			BlockFace face = getTargetFace(world, pos, getBlockState());

			ItemStack remainder = behaviour == null ? inserting.insert(stack.copy())
				: behaviour.handleInsertion(stack, face.getFace(), false);
			if (!remainder.isEmpty()) {
				stack.setCount(remainder.getCount());
				continue;
			}

			inserted.add(pair);
		}

		inserted.forEach(groupedItems.grid::remove);
		if (groupedItems.grid.isEmpty())
			ejectWholeGrid();
		else
			phase = Phase.INSERTING;
		if (!inserted.isEmpty() || chagedPhase)
			sendData();
	}

	public void ejectWholeGrid() {
		List<MechanicalCrafterTileEntity> chain = RecipeGridHandler.getAllCraftersOfChain(this);
		if (chain == null)
			return;
		chain.forEach(MechanicalCrafterTileEntity::eject);
	}

	public void eject() {
		BlockState blockState = getBlockState();
		boolean present = AllBlocks.MECHANICAL_CRAFTER.has(blockState);
		Vector3d vec = present ? Vector3d.of(blockState.get(HORIZONTAL_FACING)
			.getDirectionVec()).scale(.75f) : Vector3d.ZERO;
		Vector3d ejectPos = VecHelper.getCenterOf(pos)
			.add(vec);
		groupedItems.grid.forEach((pair, stack) -> dropItem(ejectPos, stack));
		if (!inventory.getStackInSlot(0)
			.isEmpty())
			dropItem(ejectPos, inventory.getStackInSlot(0));
		phase = Phase.IDLE;
		groupedItems = new GroupedItems();
		inventory.setStackInSlot(0, ItemStack.EMPTY);
		sendData();
	}

	public void dropItem(Vector3d ejectPos, ItemStack stack) {
		ItemEntity itemEntity = new ItemEntity(world, ejectPos.x, ejectPos.y, ejectPos.z, stack);
		itemEntity.setDefaultPickupDelay();
		world.addEntity(itemEntity);
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (world.isRemote && !isVirtual())
			return;
		if (phase == Phase.IDLE && craftingItemPresent())
			checkCompletedRecipe(false);
		if (phase == Phase.INSERTING)
			tryInsert();
	}

	public boolean craftingItemPresent() {
		return !inventory.getStackInSlot(0)
			.isEmpty();
	}

	public boolean craftingItemOrCoverPresent() {
		return !inventory.getStackInSlot(0)
			.isEmpty() || covered;
	}

	protected void checkCompletedRecipe(boolean poweredStart) {
		if (getSpeed() == 0)
			return;
		if (world.isRemote && !isVirtual())
			return;
		List<MechanicalCrafterTileEntity> chain = RecipeGridHandler.getAllCraftersOfChainIf(this,
			poweredStart ? MechanicalCrafterTileEntity::craftingItemPresent
				: MechanicalCrafterTileEntity::craftingItemOrCoverPresent,
			poweredStart);
		if (chain == null)
			return;
		chain.forEach(MechanicalCrafterTileEntity::begin);
	}

	protected void begin() {
		phase = Phase.ACCEPTING;
		groupedItems = new GroupedItems(inventory.getStackInSlot(0));
		inventory.setStackInSlot(0, ItemStack.EMPTY);
		if (RecipeGridHandler.getPrecedingCrafters(this)
			.isEmpty()) {
			phase = Phase.ASSEMBLING;
			countDown = 500;
		}
		sendData();
	}

	protected void continueIfAllPrecedingFinished() {
		List<MechanicalCrafterTileEntity> preceding = RecipeGridHandler.getPrecedingCrafters(this);
		if (preceding == null) {
			ejectWholeGrid();
			return;
		}

		for (MechanicalCrafterTileEntity mechanicalCrafterTileEntity : preceding)
			if (mechanicalCrafterTileEntity.phase != Phase.WAITING)
				return;

		phase = Phase.ASSEMBLING;
		countDown = Math.max(100, getCountDownSpeed() + 1);
	}

	@Override
	public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
		if (isItemHandlerCap(cap))
			return invSupplier.cast();
		return super.getCapability(cap, side);
	}

	public void connectivityChanged() {
		reRender = true;
		sendData();
		invSupplier.invalidate();
		invSupplier = LazyOptional.of(() -> input.getItemHandler(world, pos));
	}

	public Inventory getInventory() {
		return inventory;
	}

	@Override
	public boolean shouldRenderAsTE() {
		return true;
	}

	public void setScriptedResult(ItemStack scriptedResult) {
		this.scriptedResult = scriptedResult;
	}

}
