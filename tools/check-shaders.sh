#!/usr/bin/env bash
# Compiles our Veil fragment shaders with glslangValidator, standing in for Veil's preprocessing:
# a #version header, the VeilCamera uniform block in place of `#veil:buffer veil:camera VeilCamera`, and
# `#include ns:name` inlined from assets/ns/pinwheel/shaders/include/name.glsl.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
status=0
for shader in src/main/resources/assets/create_relativity/pinwheel/shaders/program/*.fsh; do
	out="$tmp/$(basename "$shader" .fsh).frag"
	{
		echo "#version 410 core"
		sed 's|^#veil:buffer veil:camera VeilCamera$|layout(std140) uniform VeilCameraBlock { mat4 ProjMat; mat4 IProjMat; mat4 ViewMat; mat4 IViewMat; mat3 IViewRotMat; vec3 CameraPosition; float NearPlane; vec3 CameraBobOffset; float FarPlane; } VeilCamera;|' "$shader" |
			# Includes nest; each is pasted once, where it first appears.
			perl -0777 -pe 'my %seen; 1 while s{^#include (\w+):([\w/]+)[ \t]*$}{ $seen{"$1:$2"}++ ? "" : do { local $/; open(my $f, "<", "src/main/resources/assets/$1/pinwheel/shaders/include/$2.glsl") or die "missing include $1:$2\n"; <$f> } }me'
	} > "$out"
	source="$shader"
	shader="$out" # the pattern checks below cover included code too (line numbers are in the expanded file)
	# Veil's GLSL processor re-prints shaders and appears to drop parentheses around ternaries, so
	# `x * (c ? a : b)` reaches the driver as `x * c ? a : b`. Only bare, top-level ternaries are safe.
	if perl -ne 'print "$.: $_" if /\([^()]*\?[^()]*:[^()]*\)/' "$shader" | tee "$tmp/ternary" | grep -q .; then
		echo "FAIL  $source: parenthesised ternary (Veil drops the parentheses)"
		sed 's/^/      /' "$tmp/ternary"
		status=1
		continue
	fi
	# A standalone postfix increment/decrement statement loses its semicolon in Veil's re-print
	# (seen: "unexpected IF, expecting ',' or ';'" after `n++;`). Use `n += 1;`; for-loop headers are fine.
	if perl -ne 'print "$.: $_" if /^\s*\w+(\[[^\]]*\])?\s*(\+\+|--)\s*;/' "$shader" | tee "$tmp/postfix" | grep -q .; then
		echo "FAIL  $source: standalone postfix ++/-- statement (Veil drops the semicolon)"
		sed 's/^/      /' "$tmp/postfix"
		status=1
		continue
	fi
	# Function prototypes (a declaration ending in `);` at top level) crash Veil's processor: it re-prints the
	# function node and dereferences its missing body. Define functions before their first use instead.
	if perl -ne 'print "$.: $_" if /^[A-Za-z_]\w*\s+[A-Za-z_]\w*\s*\([^;{]*\)\s*;\s*$/' "$shader" | tee "$tmp/prototype" | grep -q .; then
		echo "FAIL  $source: function prototype (crashes Veil's GLSL processor)"
		sed 's/^/      /' "$tmp/prototype"
		status=1
		continue
	fi
	if glslangValidator "$out" > "$tmp/log" 2>&1; then
		echo "ok    $source"
	else
		echo "FAIL  $source"
		sed 's/^/      /' "$tmp/log"
		status=1
	fi
done
exit $status
