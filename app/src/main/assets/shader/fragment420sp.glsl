precision mediump float;
uniform sampler2D tex_y;
uniform sampler2D tex_uv;
varying vec2 tc;
void main() {
	float _y_color = texture2D(tex_y, tc).r;
	vec4 _uv_color = texture2D(tex_uv, tc);
	float _u_color = _uv_color.a;
	float _v_color = _uv_color.r;
	float r = _y_color + 1.4075 * (_v_color - 0.5);
	float g = _y_color - 0.3455 * (_u_color - 0.5) - 0.7169 * (_v_color - 0.5);
	float b = _y_color + 1.779 * (_u_color - 0.5);
	gl_FragColor = vec4(r, g, b, 1.0);
}