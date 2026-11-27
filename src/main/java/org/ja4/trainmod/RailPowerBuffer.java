package org.ja4.trainmod;

import java.util.function.BooleanSupplier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

final class RailPowerBuffer extends SimpleEnergyHandler {
    static final int TRANSFER_LIMIT = 128;

    private final BlockEntity owner;
    private final BooleanSupplier connected;
    private final boolean input;
    private long transferTick = Long.MIN_VALUE;
    private int transferred;
    private final SnapshotJournal<Integer> budget = new SnapshotJournal<>() {
        @Override
        protected Integer createSnapshot() { return transferred; }

        @Override
        protected void revertToSnapshot(Integer snapshot) { transferred = snapshot; }
    };

    RailPowerBuffer(BlockEntity owner, int capacity, boolean input, BooleanSupplier connected) {
        super(capacity, input ? TRANSFER_LIMIT : 0, input ? capacity : TRANSFER_LIMIT);
        this.owner = owner;
        this.input = input;
        this.connected = connected;
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        return input ? transfer(amount, transaction) : 0;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return input ? 0 : transfer(amount, transaction);
    }

    int draw(int amount, TransactionContext transaction) {
        return connected.getAsBoolean() && !owner.isRemoved() ? super.extract(amount, transaction) : 0;
    }

    private int transfer(int amount, TransactionContext transaction) {
        var level = owner.getLevel();
        if (level == null || owner.isRemoved() || !connected.getAsBoolean()) return 0;
        long now = level.getGameTime();
        if (transferTick != now) {
            transferTick = now;
            transferred = 0;
        }
        int limit = Math.min(amount, TRANSFER_LIMIT - transferred);
        int accepted = input ? super.insert(limit, transaction) : super.extract(limit, transaction);
        if (accepted > 0) {
            budget.updateSnapshots(transaction);
            transferred += accepted;
        }
        return accepted;
    }

    @Override
    protected void onEnergyChanged(int previousAmount) { owner.setChanged(); }
}
