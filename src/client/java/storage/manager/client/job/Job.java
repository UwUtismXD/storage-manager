package storage.manager.client.job;

public class Job {
    public enum Type { SCAN_REGION, SORT_INPUT, WITHDRAW }

    public final Type type;
    public final String itemId;
    public final int count;

    private Job(Type type, String itemId, int count) {
        this.type = type;
        this.itemId = itemId;
        this.count = count;
    }

    public static Job scanRegion() {
        return new Job(Type.SCAN_REGION, null, 0);
    }

    public static Job sortInput() {
        return new Job(Type.SORT_INPUT, null, 0);
    }

    public static Job withdraw(String itemId, int count) {
        return new Job(Type.WITHDRAW, itemId, count);
    }

    @Override
    public String toString() {
        return switch (type) {
            case SCAN_REGION -> "SCAN_REGION";
            case SORT_INPUT -> "SORT_INPUT";
            case WITHDRAW -> "WITHDRAW " + count + "x " + itemId;
        };
    }
}
