#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

// Direction in world space: the cube is centred on the camera and not rotated.
out vec3 direction;

void main() {
    direction = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
