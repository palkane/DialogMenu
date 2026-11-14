flat out vec3 dialogmenuModelAnimation;

void dialogmenu_model_legacy(inout vec3 position, inout vec2 imageUv) {
    if (dialogmenuMarked != 0) return;
    uvec4 bytes = uvec4(round(Color * 255.0));
    uint payload = (bytes.a << 24u) | (bytes.r << 16u) | (bytes.g << 8u) | bytes.b;
#ifndef DIALOGMENU_BITMAP_MODELS
    if ((payload & 0xE0000000u) == 0xE0000000u && dialogmenu_native_scope(position)) {
        position.x += 65536.0;
        int corner = gl_VertexID % 4;
        vec2 edge = vec2(corner >= 2 ? 1.0 : 0.0, corner == 1 || corner == 2 ? 1.0 : 0.0);
        float size = float(((payload >> 11u) & 1023u) + 1u);
        int localY = int(payload & 2047u);
        if (localY >= 1024) localY -= 2048;
        position.xy += vec2(-1.0, float(localY)) + edge * (size - 8.0);
        vertexColor = vec4(1.0);
        dialogmenuMarked = 4;
        dialogmenu_slice(float(localY) + edge.y * size, int((payload >> 28u) & 1u));
        return;
    }
#endif
    mat4 projection = ProjMat * ModelViewMat;
    float centerX = -projection[3].x / projection[0].x;
    int face = int(floor((centerX - position.x) / 2000.0 + 0.5)) - 33;
    bool ce = face >= 4096;
    int record = ce ? face - 4096 : face;
    if (record < 0 || record >= (ce ? MODEL_CE_FACES : MODEL_VANILLA_FACES)) return;
    if (all(equal(bytes, uvec4(190, 239, 1, 255)))) { dialogmenuMarked = 3; return; }
    if ((payload & 0xE0000000u) != 0x40000000u) return;
    position.x += float((face + 33) * 2000);
    vec2 atlas = vec2(textureSize(Sampler0, 0));
#ifdef DIALOGMENU_BITMAP_MODELS
    vec2 edge = step(vec2(0.5), fract(UV0 * atlas));
    int corner = edge.x == 0.0 ? (edge.y == 0.0 ? 0 : 1) : (edge.y == 0.0 ? 3 : 2);
    int descriptor = int((payload >> 21u) & 127u);
    vec4 dimensions = dialogmenu_bitmap_size(descriptor);
    vec2 origin = position.xy - edge - vec2(1.0, 8.0);
#else
    int corner = gl_VertexID % 4;
    vec2 edge = vec2(corner >= 2 ? 1.0 : 0.0, corner == 1 || corner == 2 ? 1.0 : 0.0);
    vec2 origin = position.xy - edge * 8.0 - vec2(1.0);
#endif
    vec4 data = ce ? modelCeData(record * 5 + corner) : modelVanillaData(record * 5 + corner);
    float size = float(((payload >> 11u) & 1023u) + 1u);
    int localY = int(payload & 2047u);
    if (localY >= 1024) localY -= 2048;
    position.xy = origin + data.xy * size + vec2(0.0, float(localY));
#ifdef DIALOGMENU_BITMAP_MODELS
    vec2 first = floor(UV0 * atlas) - edge * (dimensions.z - 1.0);
    vec3 animation = dialogmenu_bitmap_animation(descriptor);
    float columns = dimensions.w;
    vec2 current = vec2(mod(animation.x, columns), floor(animation.x / columns)) * dimensions.xy;
    vec2 next = vec2(mod(animation.y, columns), floor(animation.y / columns)) * dimensions.xy;
    imageUv = (first + current + edge * dimensions.xy + data.zw) / atlas;
    dialogmenuModelAnimation = vec3((next - current) / atlas, animation.z);
#else
    imageUv += data.zw / atlas;
#endif
    vertexColor = ce ? modelCeData(record * 5 + 4) : modelVanillaData(record * 5 + 4);
    dialogmenuMarked = 4;
    dialogmenu_slice(data.y * size + float(localY) - 1.0, int((payload >> 28u) & 1u));
}
