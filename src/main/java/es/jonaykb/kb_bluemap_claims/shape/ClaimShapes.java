package es.jonaykb.kb_bluemap_claims.shape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.flowpowered.math.vector.Vector2d;

import de.bluecolored.bluemap.api.math.Shape;
import net.minecraft.world.level.ChunkPos;

/**
 * Turns a set of claimed chunks into BlueMap polygons: one outline per group of touching chunks,
 * with the unclaimed areas fully enclosed by it as holes.
 * Ported from opac-bluemap-integration by Gaming32 (MIT).
 */
public final class ClaimShapes {

    /** One polygon with its holes. */
    public record Area(Shape outline, Shape[] holes, int chunkCount) {}

    private ClaimShapes() {}

    public static List<Area> areas(Set<ChunkPos> chunks) {
        List<Area> out = new ArrayList<>();
        for (Set<ChunkPos> group : groups(chunks)) {
            Shape[] holes = groups(enclosedGaps(group)).stream()
                    .map(ClaimShapes::outline)
                    .toArray(Shape[]::new);
            out.add(new Area(outline(group), holes, group.size()));
        }
        return out;
    }

    /** Splits the chunks into groups that touch side by side. */
    static List<Set<ChunkPos>> groups(Set<ChunkPos> chunks) {
        List<Set<ChunkPos>> result = new ArrayList<>();
        Set<ChunkPos> visited = new HashSet<>();
        for (ChunkPos chunk : chunks) {
            if (visited.contains(chunk)) continue;
            Set<ChunkPos> group = floodFill(chunk, chunks);
            result.add(group);
            visited.addAll(group);
        }
        return result;
    }

    private static Set<ChunkPos> floodFill(ChunkPos start, Set<ChunkPos> chunks) {
        Set<ChunkPos> visited = new HashSet<>();
        Queue<ChunkPos> toVisit = new ArrayDeque<>();
        visited.add(start);
        toVisit.add(start);
        while (!toVisit.isEmpty()) {
            ChunkPos current = toVisit.remove();
            for (ChunkDirection dir : ChunkDirection.values()) {
                ChunkPos next = dir.add(current);
                if (chunks.contains(next) && visited.add(next)) toVisit.add(next);
            }
        }
        return visited;
    }

    /** Walks the border of a group clockwise, keeping the claimed chunks on the right. */
    private static Shape outline(Set<ChunkPos> chunks) {
        ChunkPos first = bound(chunks, true);
        while (!chunks.contains(first)) {
            first = ChunkDirection.RIGHT.add(first);
        }
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{first.getMinBlockX(), first.getMinBlockZ()});

        ChunkPos current = first;
        ChunkDirection direction = ChunkDirection.RIGHT;
        do {
            points.add(ChunkDirection.corner(current, direction, direction.right()));
            ChunkPos next = direction.add(current);
            if (chunks.contains(next)) {
                ChunkPos right = direction.right().add(next);
                if (chunks.contains(right)) {
                    current = right;
                    direction = direction.right();
                } else {
                    current = next;
                }
            } else {
                direction = direction.left();
            }
        } while (!current.equals(first) || direction != ChunkDirection.RIGHT);

        return new Shape(simplify(points));
    }

    /** Unclaimed chunks inside the bounding box that cannot reach its border: the holes. */
    private static Set<ChunkPos> enclosedGaps(Set<ChunkPos> chunks) {
        ChunkPos min = bound(chunks, true);
        ChunkPos max = bound(chunks, false);

        Queue<ChunkPos> toVisit = new ArrayDeque<>();
        for (int x = min.x; x <= max.x; x++) {
            for (int z = min.z; z <= max.z; z++) {
                if (x > min.x && x < max.x && z > min.z && z < max.z) continue;
                ChunkPos chunk = new ChunkPos(x, z);
                if (!chunks.contains(chunk)) toVisit.add(chunk);
            }
        }

        Set<ChunkPos> outside = new HashSet<>(toVisit);
        while (!toVisit.isEmpty()) {
            ChunkPos chunk = toVisit.remove();
            for (ChunkDirection dir : ChunkDirection.values()) {
                ChunkPos next = dir.add(chunk);
                if (next.x < min.x || next.x > max.x || next.z < min.z || next.z > max.z) continue;
                if (chunks.contains(next) || !outside.add(next)) continue;
                toVisit.add(next);
            }
        }

        Set<ChunkPos> gaps = new HashSet<>();
        for (int x = min.x; x <= max.x; x++) {
            for (int z = min.z; z <= max.z; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (!chunks.contains(chunk) && !outside.contains(chunk)) gaps.add(chunk);
            }
        }
        return gaps;
    }

    /** Drops the points in the middle of straight lines. */
    private static List<Vector2d> simplify(List<double[]> raw) {
        List<Vector2d> points = raw.stream().map(p -> new Vector2d(p[0], p[1])).toList();
        if (points.size() < 4) return points;

        List<Vector2d> result = new ArrayList<>();
        result.add(points.get(0));
        for (int i = 1; i < points.size() - 1; i++) {
            Vector2d prev = points.get(i - 1);
            Vector2d point = points.get(i);
            Vector2d next = points.get(i + 1);
            if (!point.sub(prev).normalize().equals(next.sub(point).normalize())) {
                result.add(point);
            }
        }
        Vector2d last = points.get(points.size() - 1);
        if (!last.equals(points.get(0))) result.add(last);
        return result;
    }

    private static ChunkPos bound(Iterable<ChunkPos> chunks, boolean min) {
        int x = min ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        int z = x;
        for (ChunkPos pos : chunks) {
            x = min ? Math.min(x, pos.x) : Math.max(x, pos.x);
            z = min ? Math.min(z, pos.z) : Math.max(z, pos.z);
        }
        return new ChunkPos(x, z);
    }
}
