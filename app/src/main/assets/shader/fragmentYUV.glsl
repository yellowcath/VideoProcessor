precision mediump float;
uniform sampler2D tex_y;
uniform sampler2D tex_u;
uniform sampler2D tex_v;
varying vec2 tc;
void main() {
    vec4 c = vec4((texture2D(tex_y, tc).r - 16./255.) * 1.164);
    vec4 U = vec4(texture2D(tex_u, tc).r - 128./255.);
    vec4 V = vec4(texture2D(tex_v, tc).r - 128./255.);
    c += V * vec4(1.596, -0.813, 0, 0);
    c += U * vec4(0, -0.392, 2.017, 0);
    c.a = 1.0;
    gl_FragColor = c;
}