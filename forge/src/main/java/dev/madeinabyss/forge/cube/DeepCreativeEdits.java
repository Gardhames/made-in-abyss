package dev.madeinabyss.forge.cube;

import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.BlockCoordinate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** First authoritative interaction slice: creative, inert full blocks, global integer coordinates. */
public final class DeepCreativeEdits {
    private DeepCreativeEdits() {}
    public record Request(boolean place, BlockPos clicked, Direction face, Vec3 hit, int expectedState) {}
    public record Result(boolean accepted, String reason, BlockPos changed, int state) {}

    public static Result apply(SparseCubeSpace space, Request request, Vec3 eye, AABB body,
                               boolean creative, BlockState held, double reach) {
        if (!creative) return reject("creative_required");
        var result = validate(space,request,eye,body,held,reach);
        if(result.accepted()) space.setBlock(coordinate(result.changed()),result.state());
        return result;
    }
    /** Geometry only: permissions and native item effects belong to the caller. Does not mutate resident data. */
    public static Result validate(SparseCubeSpace space, Request request, Vec3 eye, AABB body,
                                  BlockState held, double reach) {
        if (request == null || request.clicked() == null || request.face() == null
                || !finite(request.hit()) || !finite(eye) || !Double.isFinite(reach) || reach <= 0 || reach > 32)
            return reject("invalid_request");
        BlockPos clicked = request.clicked();
        if (clicked.getY() < -24000 || clicked.getY() >= -64) return reject("outside_deep_slice");
        if (eye.distanceToSqr(request.hit()) > reach * reach) return reject("out_of_reach");
        Vec3 local = request.hit().subtract(clicked.getX(), clicked.getY(), clicked.getZ());
        if (local.x < -.001 || local.x > 1.001 || local.y < -.001 || local.y > 1.001 || local.z < -.001 || local.z > 1.001)
            return reject("invalid_hit");
        var current = space.block(coordinate(clicked));
        if (current.isEmpty()) return reject("unknown_clicked_cube");
        if (current.getAsInt() != request.expectedState()) return reject("stale_state");
        if (!inert(Block.stateById(current.getAsInt()))) return reject("unsupported_clicked_block");
        var view = new CubeBlockView(space,-24000,23936);
        var direction = request.hit().subtract(eye);
        if (direction.lengthSqr() < 1.0e-12) return reject("invalid_hit");
        try {
            var hit = view.clip(new ClipContext(eye,request.hit().add(direction.normalize().scale(.02)),
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,null));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(clicked)
                    || hit.getDirection() != request.face()) return reject("occluded_or_wrong_face");
        } catch (CubeBlockView.MissingCubeException unknown) { return reject("unknown_ray_path"); }
        catch (IllegalArgumentException invalidBounds) { return reject("invalid_ray_bounds"); }
        BlockPos changed = clicked;
        int next = Block.getId(Blocks.AIR.defaultBlockState());
        if (request.place()) {
            if (held == null || !inert(held)) return reject("unsupported_held_block");
            try {
                changed = new BlockPos(Math.addExact(clicked.getX(),request.face().getStepX()),
                        Math.addExact(clicked.getY(),request.face().getStepY()),Math.addExact(clicked.getZ(),request.face().getStepZ()));
            } catch (ArithmeticException overflow) { return reject("coordinate_overflow"); }
            if (changed.getY() < -24000 || changed.getY() >= -64) return reject("outside_deep_slice");
            var destination = space.block(coordinate(changed));
            if (destination.isEmpty()) return reject("unknown_destination");
            if (!Block.stateById(destination.getAsInt()).isAir()) return reject("occupied_destination");
            if (body == null || body.intersects(new AABB(changed))) return reject("player_collision");
            next = Block.getId(held);
        }
        return new Result(true,"accepted",changed,next);
    }
    public static boolean inert(BlockState state) {
        return state != null && (state.is(Blocks.STONE) || state.is(Blocks.GOLD_BLOCK)
                || state.is(Blocks.DIAMOND_BLOCK) || state.is(Blocks.EMERALD_BLOCK) || state.is(Blocks.GLOWSTONE));
    }
    private static BlockCoordinate coordinate(BlockPos pos) { return new BlockCoordinate(pos.getX(),pos.getY(),pos.getZ()); }
    private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static Result reject(String reason) { return new Result(false,reason,null,0); }
}
