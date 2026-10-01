#!/usr/bin/env bash
# Compiles our Veil fragment shaders with glslangValidator, standing in for Veil's preprocessing:
# a #version header and the VeilCamera uniform block in place of `#veil:buffer veil:camera VeilCamera`.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
status=0
for shader in src/main/resources/assets/create_relativity/pinwheel/shaders/program/*.fsh; do
	out="$tmp/$(basename "$shader" .fsh).frag"
	{
		echo "#version 410 core"
		sed 's|^#veil:buffer veil:camera VeilCamera$|layout(std140) uniform VeilCameraBlock { mat4 ProjMat; mat4 IProjMat; mat4 ViewMat; mat4 IViewMat; mat3 IViewRotMat; vec3 CameraPosition; float NearPlane; vec3 CameraBobOffset; float FarPlane; } VeilCamera;|' "$shader"
	} > "$out"
	# Veil's GLSL processor re-prints shaders and appears to drop parentheses around ternaries, so
	# `x * (c ? a : b)` reaches the driver as `x * c ? a : b`. Only bare, top-level ternaries are safe.
	if perl -ne 'print "$.: $_" if /\([^()]*\?[^()]*:[^()]*\)/' "$shader" | tee "$tmp/ternary" | grep -q .; then
		echo "FAIL  $shader: parenthesised ternary (Veil drops the parentheses)"
		sed 's/^/      /' "$tmp/ternary"
		status=1
		continue
	fi
	# A standalone postfix increment/decrement statement loses its semicolon in Veil's re-print
	# (seen: "unexpected IF, expecting ',' or ';'" after `n++;`). Use `n += 1;`; for-loop headers are fine.
	if perl -ne 'print "$.: $_" if /^\s*\w+(\[[^\]]*\])?\s*(\+\+|--)\s*;/' "$shader" | tee "$tmp/postfix" | grep -q .; then
		echo "FAIL  $shader: standalone postfix ++/-- statement (Veil drops the semicolon)"
		sed 's/^/      /' "$tmp/postfix"
		status=1
		continue
	fi
	if glslangValidator "$out" > "$tmp/log" 2>&1; then
		echo "ok    $shader"
	else
		echo "FAIL  $shader"
		sed 's/^/      /' "$tmp/log"
		status=1
	fi
done
exit $status
