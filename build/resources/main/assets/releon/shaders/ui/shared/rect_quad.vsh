#version 150

#moj_import <releon:common.glsl>

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec2 FragCoord;
flat out int QuadIndex;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    FragCoord = rvertexcoord(gl_VertexID);
    QuadIndex = gl_VertexID / 4;
}
