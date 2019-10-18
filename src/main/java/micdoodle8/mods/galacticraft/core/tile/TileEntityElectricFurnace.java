package micdoodle8.mods.galacticraft.core.tile;

import micdoodle8.mods.galacticraft.core.GCBlocks;
import micdoodle8.mods.galacticraft.core.blocks.BlockMachineTiered;
import micdoodle8.mods.galacticraft.core.energy.item.ItemElectricBase;
import micdoodle8.mods.galacticraft.core.energy.tile.EnergyStorageTile;
import micdoodle8.mods.galacticraft.core.energy.tile.TileBaseElectricBlockWithInventory;
import micdoodle8.mods.galacticraft.core.util.ConfigManagerCore;
import micdoodle8.mods.galacticraft.core.util.GCCoreUtil;
import micdoodle8.mods.miccore.Annotations.NetworkedField;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fml.relauncher.Side;

import java.util.HashSet;
import java.util.Set;

public class TileEntityElectricFurnace extends TileBaseElectricBlockWithInventory implements ISidedInventory, IMachineSides
{
    //The electric furnace is 50% faster than a vanilla Furnace
    //but at a cost of some inefficiency:
    //It uses 46800 gJ to smelt 8 ingots quickly
    //compared with the energy generated by 1 coal which is 38400 gJ
    //
    //The efficiency can be increased using a Tier 2 furnace

    public static int PROCESS_TIME_REQUIRED = 130;

    @NetworkedField(targetSide = Side.CLIENT)
    public int processTimeRequired = PROCESS_TIME_REQUIRED;

    @NetworkedField(targetSide = Side.CLIENT)
    public int processTicks = 0;

    public final Set<EntityPlayer> playersUsing = new HashSet<EntityPlayer>();

    private boolean initialised = false;

    public TileEntityElectricFurnace()
    {
        this(1);
    }

    /*
     * @param tier: 1 = Electric Furnace  2 = Electric Arc Furnace
     */
    public TileEntityElectricFurnace(int tier)
    {
        super(tier == 1 ? "tile.machine.2.name" : "tile.machine.7.name");
        this.initialised = true;
	    this.inventory = NonNullList.withSize(4, ItemStack.EMPTY);
        if (tier == 1)
        {
            this.storage.setMaxExtract(ConfigManagerCore.hardMode ? 60 : 45);
            return;
        }

        this.setTier2();
    }

    private void setTier2()
    {
        this.storage.setCapacity(25000);
        this.storage.setMaxExtract(ConfigManagerCore.hardMode ? 90 : 60);
        this.processTimeRequired = 100;
        this.setTierGC(2);
    }

    @Override
    public void update()
    {
        if (!this.initialised)
        {
            int metadata = this.getBlockMetadata();
            //for version update compatibility
            Block b = this.world.getBlockState(this.getPos()).getBlock();
            if (b == GCBlocks.machineBase)
            {
                this.world.setBlockState(this.getPos(), GCBlocks.machineTiered.getDefaultState()/*,s 4*/, 2);
            }
            else if (metadata >= 8)
            {
                this.setTier2();
            }
            this.initialised = true;
        }

        super.update();

        if (!this.world.isRemote)
        {
            if (this.canProcess())
            {
                if (this.hasEnoughEnergyToRun)
                {
                    //100% extra speed boost for Tier 2 machine if powered by Tier 2 power
                    if (this.tierGC >= 2)
                    {
                        this.processTimeRequired = PROCESS_TIME_REQUIRED / 2 / this.poweredByTierGC;
                    }

                    if (this.processTicks == 0)
                    {
                        this.processTicks = this.processTimeRequired;
                    }
                    else
                    {
                        if (--this.processTicks <= 0)
                        {
                            this.smeltItem();
                            this.processTicks = this.canProcess() ? this.processTimeRequired : 0;
                        }
                    }
                }
                else if (this.processTicks > 0 && this.processTicks < this.processTimeRequired)
                {
                    //Apply a "cooling down" process if the electric furnace runs out of energy while smelting
                    if (this.world.rand.nextInt(4) == 0)
                    {
                        this.processTicks++;
                    }
                }
            }
            else
            {
                this.processTicks = 0;
            }
        } else
        {
            //Smoother client side animation before the networked fields get updated
            if (this.processTicks > 0 && this.processTicks < this.processTimeRequired)
            {
                this.processTicks--;
            }
        }
    }

    /**
     * @return Is this machine able to process its specific task?
     */
    public boolean canProcess()
    {
        if (this.getInventory().get(1).isEmpty())
        {
            return false;
        }
        ItemStack result = FurnaceRecipes.instance().getSmeltingResult(this.getInventory().get(1));
        if (result.isEmpty())
        {
            return false;
        }


		if (this.tierGC == 1)
        {
	        if (!this.getInventory().get(2).isEmpty())
            {
                return (this.getInventory().get(2).isItemEqual(result) && this.getInventory().get(2).getCount() < 64);
            }
        }
        
        //Electric Arc Furnace
        if (this.getInventory().get(2).isEmpty() || this.getInventory().get(3).isEmpty())
        {
            return true;
        }
        int space = 0;
        if (this.getInventory().get(2).isItemEqual(result))
        {
            space = 64 - this.getInventory().get(2).getCount();
        }
        if (this.getInventory().get(3).isItemEqual(result))
        {
            space += 64 - this.getInventory().get(3).getCount();
        }
        return space >= 2;
    }

    /**
     * Turn one item from the furnace source stack into the appropriate smelted
     * item in the furnace result stack
     */
    public void smeltItem()
    {
        if (this.canProcess())
        {
            ItemStack resultItemStack = FurnaceRecipes.instance().getSmeltingResult(this.getInventory().get(1));
            boolean doubleResult = false;
            if (this.tierGC > 1)
            {
                String nameSmelted = this.getInventory().get(1).getUnlocalizedName().toLowerCase();
                if ((resultItemStack.getUnlocalizedName().toLowerCase().contains("ingot") || resultItemStack.getItem() == Items.QUARTZ) && (nameSmelted.contains("ore") || nameSmelted.contains("raw") || nameSmelted.contains("moon") || nameSmelted.contains("mars") || nameSmelted.contains("shard")))
                {
                    doubleResult = true;
                }
            }

            if (doubleResult)
            {
                int space2 = 0;
                int space3 = 0;
                if (this.getInventory().get(2).isEmpty())
                {
                    this.getInventory().set(2, resultItemStack.copy());
                    this.getInventory().get(2).grow(resultItemStack.getCount());
                    space2 = 2;
                }
                else if (this.getInventory().get(2).isItemEqual(resultItemStack))
                {
                    space2 = (64 - this.getInventory().get(2).getCount()) / resultItemStack.getCount();
                    if (space2 > 2) space2 = 2;
                    this.getInventory().get(2).grow(resultItemStack.getCount() * space2);
                }
                if (space2 < 2)
                {
                    if (this.getInventory().get(3).isEmpty())
                    {
                        this.getInventory().set(3, resultItemStack.copy());
                        if (space2 == 0)
                        {
                            this.getInventory().get(3).grow(resultItemStack.getCount());
                        }
                    }
                    else if (this.getInventory().get(3).isItemEqual(resultItemStack))
                    {
                        space3 = (64 - this.getInventory().get(3).getCount()) / resultItemStack.getCount();
                        if (space3 > 2 - space2) space3 = 2 - space2;
                        this.getInventory().get(3).grow(resultItemStack.getCount() * space3);
                    }
                }
            }
            else if (this.getInventory().get(2).isEmpty())
            {
                this.getInventory().set(2, resultItemStack.copy());
            }
            else if (this.getInventory().get(2).isItemEqual(resultItemStack))
            {
                this.getInventory().get(2).grow(resultItemStack.getCount());
            }

            this.getInventory().get(1).shrink(1);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        super.readFromNBT(nbt);
        if (this.storage.getEnergyStoredGC() > EnergyStorageTile.STANDARD_CAPACITY)
        {
            this.setTier2();
            this.initialised = true;
        }
        else
        {
            this.initialised = false;
        }
        this.processTicks = nbt.getInteger("smeltingTicks");

        this.readMachineSidesFromNBT(nbt);  //Needed by IMachineSides
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt)
    {
        if (this.tierGC == 1 && this.storage.getEnergyStoredGC() > EnergyStorageTile.STANDARD_CAPACITY)
        {
            this.storage.setEnergyStored(EnergyStorageTile.STANDARD_CAPACITY);
        }
        super.writeToNBT(nbt);
        nbt.setInteger("smeltingTicks", this.processTicks);

        this.addMachineSidesToNBT(nbt);  //Needed by IMachineSides

        return nbt;
    }

//    @Override
//    public boolean hasCustomName()
//    {
//        return true;
//    }

    /**
     * Returns true if automation is allowed to insert the given stack (ignoring
     * stack size) into the given slot.
     */
    @Override
    public boolean isItemValidForSlot(int slotID, ItemStack itemStack)
    {
        if (itemStack.isEmpty())
        {
            return false;
        }
        return slotID == 1 ? !FurnaceRecipes.instance().getSmeltingResult(itemStack).isEmpty() : slotID == 0 && ItemElectricBase.isElectricItem(itemStack.getItem());
    }

    @Override
    public int[] getSlotsForFace(EnumFacing side)
    {
        if (this.tierGC == 2)
        {
            return new int[] { 0, 1, 2, 3 };
        }
        return new int[] { 0, 1, 2 };
    }

    @Override
    public boolean canInsertItem(int slotID, ItemStack par2ItemStack, EnumFacing par3)
    {
        return this.isItemValidForSlot(slotID, par2ItemStack);
    }

    @Override
    public boolean canExtractItem(int slotID, ItemStack par2ItemStack, EnumFacing par3)
    {
        return slotID == 2 || this.tierGC == 2 && slotID == 3;
    }

    @Override
    public boolean shouldUseEnergy()
    {
        return this.canProcess();
    }

    @Override
    public boolean hasCustomName()
    {
        return false;
    }

    @Override
    public EnumFacing getFront()
    {
        IBlockState state = this.world.getBlockState(getPos()); 
        if (state.getBlock() instanceof BlockMachineTiered)
        {
            return state.getValue(BlockMachineTiered.FACING);
        }
        return EnumFacing.NORTH;
    }

    @Override
    public EnumFacing getElectricInputDirection()
    {
        switch (this.getSide(MachineSide.ELECTRIC_IN))
        {
        case RIGHT:
            return getFront().rotateYCCW();
        case REAR:
            return getFront().getOpposite();
        case TOP:
            return EnumFacing.UP;
        case BOTTOM:
            return EnumFacing.DOWN;
        case LEFT:
        default:
            return getFront().rotateY();
        }
    }

    //------------------
    //Added these methods and field to implement IMachineSides properly 
    //------------------
    @Override
    public MachineSide[] listConfigurableSides()
    {
        return new MachineSide[] { MachineSide.ELECTRIC_IN };
    }

    @Override
    public Face[] listDefaultFaces()
    {
        return new Face[] { Face.LEFT };
    }
    
    private MachineSidePack[] machineSides;

    @Override
    public synchronized MachineSidePack[] getAllMachineSides()
    {
        if (this.machineSides == null)
        {
            this.initialiseSides();
        }

        return this.machineSides;
    }

    @Override
    public void setupMachineSides(int length)
    {
        this.machineSides = new MachineSidePack[length];
    }
    
    @Override
    public void onLoad()
    {
        this.clientOnLoad();
    }
    
    @Override
    public IMachineSidesProperties getConfigurationType()
    {
        return BlockMachineTiered.MACHINESIDES_RENDERTYPE;
    }
    //------------------END OF IMachineSides implementation
}
