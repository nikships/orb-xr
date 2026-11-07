attribute vec3 aPosition;
attribute vec3 aNormal;
uniform mat4 uModel;
uniform mat4 uModelViewProjection;
uniform mat3 uNormalMatrix;
varying vec3 vNormal;
varying vec3 vObjectNormal;
varying vec3 vLocalPosition;
void main() {
  vNormal = normalize(uNormalMatrix * aNormal);
  vObjectNormal = aNormal;
  vLocalPosition = (uModel * vec4(aPosition, 1.0)).xyz;
  gl_Position = uModelViewProjection * vec4(aPosition, 1.0);
}
