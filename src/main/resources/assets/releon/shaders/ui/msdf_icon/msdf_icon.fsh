#version 150

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 TexCoord;
in vec4 VertexColor;
flat in float DistanceRange;

out vec4 OutColor;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

float screenPxRange() {
    vec2 atlasSize = vec2(textureSize(Sampler0, 0));
    vec2 unitRange = vec2(DistanceRange) / atlasSize;
    vec2 screenTexSize = vec2(1.0) / fwidth(TexCoord);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

float msdfAlpha(vec2 uv, float pxRange) {
    vec3 msd = texture(Sampler0, uv).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    return clamp(pxRange * (sd - 0.5) + 0.5, 0.0, 1.0);
}

void main() {
    float pxRange = screenPxRange();
    vec2 dx = dFdx(TexCoord) * 0.22;
    vec2 dy = dFdy(TexCoord) * 0.22;
    float alpha = msdfAlpha(TexCoord, pxRange) * 0.62;
    alpha += msdfAlpha(TexCoord + dx, pxRange) * 0.095;
    alpha += msdfAlpha(TexCoord - dx, pxRange) * 0.095;
    alpha += msdfAlpha(TexCoord + dy, pxRange) * 0.095;
    alpha += msdfAlpha(TexCoord - dy, pxRange) * 0.095;
    alpha = clamp((alpha - 0.5) * 1.34 + 0.5, 0.0, 1.0) * VertexColor.a;
    if (alpha < 0.004) {
        discard;
    }
    OutColor = vec4(VertexColor.rgb, alpha) * ColorModulator;
}
