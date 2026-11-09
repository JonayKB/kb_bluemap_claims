package es.jonaykb.kb_bluemap_claims.shape;

import net.minecraft.world.level.ChunkPos;

/**
 * The four directions on the chunk grid, clockwise.
 * Ported from opac-bluemap-integration by Gaming32 (MIT).
 */
enum ChunkDirection {
    LEFT(-1, 0),
    FORWARD(0, 1),
    RIGHT(1, 0),
    BACKWARD(0, -1);

    final int x;
    final int z;

    ChunkDirection(int x, int z) {
        this.x = x;
        this.z = z;
    }

    ChunkDirection left() {
        return rotate(-1);
    }

    ChunkDirection right() {
        return rotate(1);
    }

    ChunkDirection opposite() {
        return rotate(2);
    }

    private ChunkDirection rotate(int clockwise) {
        return values()[(ordinal() + clockwise) & 3];
    }

    ChunkPos add(ChunkPos pos) {
        return new ChunkPos(pos.x + x, pos.z + z);
    }

    /** Block coordinate of the chunk edge that faces this direction. */
    int edge(ChunkPos pos) {
        return switch (this) {
            case LEFT -> pos.getMinBlockX();
            case FORWARD -> pos.getMaxBlockZ() + 1;
            case RIGHT -> pos.getMaxBlockX() + 1;
            case BACKWARD -> pos.getMinBlockZ();
        };
    }

    /** Block x/z of the chunk corner between two perpendicular directions. */
    static double[] corner(ChunkPos pos, ChunkDirection a, ChunkDirection b) {
        if (a == b || a.opposite() == b) {
            throw new IllegalArgumentException("A corner needs two perpendicular directions");
        }
        return a.x != 0
                ? new double[]{a.edge(pos), b.edge(pos)}
                : new double[]{b.edge(pos), a.edge(pos)};
    }
}
