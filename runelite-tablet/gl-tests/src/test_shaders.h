/*
 * test_shaders.h — GLSL 330 shader source strings for VirGL rendering tests.
 *
 * These shaders test the specific GL features RuneLite requires:
 * - layout(std140) uniform blocks
 * - sampler2DArray + textureLod()
 * - noperspective centroid interpolation
 * - inverse(mat3)
 * - textureSize()
 * - Full scene emulation (reversed-Z + texarray + noperspective + UBO)
 */
#ifndef TEST_SHADERS_H
#define TEST_SHADERS_H

/* ===== Module 2: Basic Shader Compilation ===== */

static const char *SHADER_VERT_BASIC =
    "#version 330\n"
    "\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "\n"
    "layout(std140) uniform Matrices {\n"
    "    mat4 uProjection;\n"
    "    mat4 uView;\n"
    "};\n"
    "\n"
    "out vec4 vColor;\n"
    "\n"
    "void main() {\n"
    "    gl_Position = uProjection * uView * vec4(aPosition, 1.0);\n"
    "    vColor = aColor;\n"
    "}\n";

static const char *SHADER_FRAG_BASIC =
    "#version 330\n"
    "\n"
    "in vec4 vColor;\n"
    "out vec4 fragColor;\n"
    "\n"
    "void main() {\n"
    "    fragColor = vColor;\n"
    "}\n";

/* ===== sampler2DArray + textureLod() ===== */

static const char *SHADER_FRAG_TEXARRAY =
    "#version 330\n"
    "\n"
    "uniform sampler2DArray uTexArray;\n"
    "\n"
    "in vec2 vTexCoord;\n"
    "flat in int vLayer;\n"
    "out vec4 fragColor;\n"
    "\n"
    "void main() {\n"
    "    fragColor = textureLod(uTexArray, vec3(vTexCoord, float(vLayer)), 0.0);\n"
    "}\n";

/* ===== noperspective centroid interpolation ===== */

static const char *SHADER_VERT_NOPERSP =
    "#version 330\n"
    "\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "\n"
    "noperspective centroid out vec4 vColor;\n"
    "\n"
    "void main() {\n"
    "    gl_Position = vec4(aPosition, 1.0);\n"
    "    vColor = aColor;\n"
    "}\n";

static const char *SHADER_FRAG_NOPERSP =
    "#version 330\n"
    "\n"
    "noperspective centroid in vec4 vColor;\n"
    "out vec4 fragColor;\n"
    "\n"
    "void main() {\n"
    "    fragColor = vColor;\n"
    "}\n";

/* ===== smooth interpolation (comparison reference) ===== */

static const char *SHADER_VERT_SMOOTH =
    "#version 330\n"
    "\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "\n"
    "smooth out vec4 vColor;\n"
    "\n"
    "void main() {\n"
    "    gl_Position = vec4(aPosition, 1.0);\n"
    "    vColor = aColor;\n"
    "}\n";

/* ===== inverse(mat3) colorblind test ===== */

static const char *SHADER_FRAG_COLORBLIND =
    "#version 330\n"
    "\n"
    "in vec4 vColor;\n"
    "out vec4 fragColor;\n"
    "\n"
    "void main() {\n"
    "    /* Test inverse(mat3) — used by RuneLite colorblind mode */\n"
    "    mat3 colorMatrix = mat3(\n"
    "        0.625, 0.375, 0.0,\n"
    "        0.7,   0.3,   0.0,\n"
    "        0.0,   0.3,   0.7\n"
    "    );\n"
    "    mat3 invMatrix = inverse(colorMatrix);\n"
    "    vec3 adjusted = invMatrix * vColor.rgb;\n"
    "    fragColor = vec4(adjusted, vColor.a);\n"
    "}\n";

/* ===== textureSize() test ===== */

static const char *SHADER_FRAG_TEXTURESIZE =
    "#version 330\n"
    "\n"
    "uniform sampler2D uTexture;\n"
    "out vec4 fragColor;\n"
    "\n"
    "void main() {\n"
    "    ivec2 texSize = textureSize(uTexture, 0);\n"
    "    /* Encode texture size as color for validation */\n"
    "    fragColor = vec4(float(texSize.x) / 1024.0, float(texSize.y) / 1024.0, 0.0, 1.0);\n"
    "}\n";

/* ===== Module 8: Full Scene Emulation ===== */

/* Scene vertex shader: reversed-Z projection + UBO + texarray coords */
static const char *SHADER_VERT_SCENE =
    "#version 330\n"
    "\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "layout(location = 2) in vec2 aTexCoord;\n"
    "layout(location = 3) in float aTexLayer;\n"
    "\n"
    "layout(std140) uniform SceneUniforms {\n"
    "    mat4 uProjection;    /* reversed-Z projection */\n"
    "    mat4 uView;\n"
    "    vec4 uFogParams;     /* x=start, y=end, z=density, w=unused */\n"
    "    vec4 uFogColor;\n"
    "};\n"
    "\n"
    "noperspective centroid out vec4 vColor;\n"
    "out vec3 vTexCoord;  /* xy = uv, z = layer */\n"
    "out float vFogFactor;\n"
    "\n"
    "void main() {\n"
    "    vec4 viewPos = uView * vec4(aPosition, 1.0);\n"
    "    gl_Position = uProjection * viewPos;\n"
    "    vColor = aColor;\n"
    "    vTexCoord = vec3(aTexCoord, aTexLayer);\n"
    "    \n"
    "    /* Linear fog based on view distance */\n"
    "    float dist = length(viewPos.xyz);\n"
    "    vFogFactor = clamp((uFogParams.y - dist) / (uFogParams.y - uFogParams.x), 0.0, 1.0);\n"
    "}\n";

/* Scene fragment shader: texarray + fog + colorblind-compatible */
static const char *SHADER_FRAG_SCENE =
    "#version 330\n"
    "\n"
    "uniform sampler2DArray uTexArray;\n"
    "\n"
    "layout(std140) uniform SceneUniforms {\n"
    "    mat4 uProjection;\n"
    "    mat4 uView;\n"
    "    vec4 uFogParams;\n"
    "    vec4 uFogColor;\n"
    "};\n"
    "\n"
    "noperspective centroid in vec4 vColor;\n"
    "in vec3 vTexCoord;\n"
    "in float vFogFactor;\n"
    "\n"
    "out vec4 fragColor;\n"
    "\n"
    "void main() {\n"
    "    vec4 texColor = textureLod(uTexArray, vTexCoord, 0.0);\n"
    "    vec4 color = texColor * vColor;\n"
    "    \n"
    "    /* Apply fog */\n"
    "    color.rgb = mix(uFogColor.rgb, color.rgb, vFogFactor);\n"
    "    \n"
    "    fragColor = color;\n"
    "}\n";

#endif /* TEST_SHADERS_H */
