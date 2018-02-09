uniform mat4 uMVPMatrix;
attribute vec4 vPosition;
attribute vec2 a_texCoord;
varying vec2 tc;
void main() {
    gl_Position = uMVPMatrix * vPosition;
    tc = a_texCoord;
}