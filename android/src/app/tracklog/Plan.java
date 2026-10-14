package app.tracklog;

/**
 * A planned stage: a name and two places (where the stage starts and where it finishes, typed or pasted from a map), and no road yet.
 * Plain Java with no Android classes.
 *
 * The first drive teaches the app the road (docs/stage-algorithm.md section 11); when that drive has been turned into a Stage, the plan
 * file is replaced by a stage.txt in the same folder under the same id (StageStore.save), so everything that pointed at the plan now
 * points at the stage. A plan has no runs. The two places need not be on the road: the lines go on the road the car drove, at the
 * nearest point.
 */
final class Plan {

    final String id;
    private volatile String name;
    final long createdUtcMs;
    final double startLat, startLon, finishLat, finishLon;

    Plan(String id, String name, long createdUtcMs, double startLat, double startLon, double finishLat, double finishLon) {
        this.id = id;
        this.name = Stage.cleanName(name);
        this.createdUtcMs = createdUtcMs;
        this.startLat = startLat;
        this.startLon = startLon;
        this.finishLat = finishLat;
        this.finishLon = finishLon;
    }

    String name() {
        return name;
    }

    void rename(String newName) {
        name = Stage.cleanName(newName);
    }

    /** The straight-line distance between the two places, metres. */
    double straightM() {
        return Geo.distance(startLat, startLon, finishLat, finishLon);
    }
}
