import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.animal.golem.CopperGolemModel;
import net.minecraft.client.model.object.equipment.ShieldModel;
import net.minecraft.client.model.object.statue.CopperGolemStatueModel;
import net.minecraft.client.model.object.skull.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.util.Unit;
import org.joml.Vector3f;

/** Build-time export from the matching vanilla factories, without launching a client. */
public final class NativeInventoryGeometry {
    private static JsonObject export(ModelPart root) {
        JsonArray faces = new JsonArray();
        root.visit(new PoseStack(), (pose, part, index, cube) -> {
            for (var polygon : cube.polygons) {
                JsonArray vertices = new JsonArray();
                for (var vertex : polygon.vertices()) {
                    var p = new Vector3f(vertex.worldX(), vertex.worldY(), vertex.worldZ());
                    pose.pose().transformPosition(p);
                    var row = new JsonArray();
                    for (float v : new float[]{p.x, p.y, p.z, vertex.u(), vertex.v()}) {
                        if (!Float.isFinite(v)) throw new IllegalStateException("Non-finite native vertex");
                        row.add(v);
                    }
                    vertices.add(row);
                }
                JsonObject face = new JsonObject();
                face.addProperty("part", part);
                face.addProperty("cube", index);
                face.add("vertices", vertices);
                faces.add(face);
            }
        });
        var result = new JsonObject();
        result.addProperty("face_count", faces.size());
        result.add("faces", faces);
        return result;
    }

    public static void main(String[] args) throws Exception {
        var root = new JsonParser().parse(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var models = root.getAsJsonObject("models");
        if (args.length == 3 && args[2].equals("beds")) {
            // Run against 26.1, before beds moved to JSON block models.
            var renderer = Class.forName("net.minecraft.client.renderer.blockentity.BedRenderer");
            for (String part : List.of("head", "foot")) {
                String method = part.equals("head") ? "createHeadLayer" : "createFootLayer";
                var layer = (LayerDefinition) renderer.getMethod(method).invoke(null);
                models.add("bed_" + part, export(layer.bakeRoot()));
            }
            Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(root));
            return;
        }
        var skull = new SkullModel(SkullModel.createMobHeadLayer().bakeRoot());
        skull.setupAnim(new SkullModelBase.State());
        models.add("skull", export(skull.root()));
        var player = new SkullModel(SkullModel.createHumanoidHeadLayer().bakeRoot());
        player.setupAnim(new SkullModelBase.State());
        models.add("player_head", export(player.root()));
        var dragon = new DragonHeadModel(DragonHeadModel.createHeadLayer().bakeRoot());
        dragon.setupAnim(new SkullModelBase.State());
        models.add("dragon_head", export(dragon.root()));
        var piglin = new PiglinHeadModel(LayerDefinition.create(PiglinHeadModel.createHeadModel(), 64, 64).bakeRoot());
        piglin.setupAnim(new SkullModelBase.State());
        models.add("piglin_head", export(piglin.root()));
        models.add("conduit", export(ConduitRenderer.createShellLayer().bakeRoot()));
        var golem = new CopperGolemStatueModel(CopperGolemModel.createBodyLayer().bakeRoot());
        golem.setupAnim(Unit.INSTANCE);
        models.add("copper_golem_statue", export(golem.root()));
        models.add("shield", export(ShieldModel.createLayer().bakeRoot()));
        models.add("pot_base", export(DecoratedPotRenderer.createBaseLayer().bakeRoot()));
        models.add("pot_sides", export(DecoratedPotRenderer.createSidesLayer().bakeRoot()));
        root.addProperty("scope", "Native default inventory geometry; no world states or player-specific components");
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        for (var item : models.entrySet())
            System.out.println(item.getKey() + ": " + item.getValue().getAsJsonObject().get("face_count"));
    }
}
