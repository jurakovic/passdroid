#!/bin/bash

# Encrypts a Passdroid++ XML file into the app's encrypted export format
# ("sq2"), so that a file made on a computer can be imported in the app:
#
#   tools/encrypt-export.sh passwords.xml passwords.sq2
#
# It asks for a password, which the app asks for at the import. It doesn't
# have to be the master password: the imported entries are stored with the
# app's own key. An import replaces all entries in the app.
#
# Needs bash, openssl 1.1.1 or later, od and head, which Git Bash for Windows
# has. The XML format and the encrypted format are described in INTERNALS.md,
# "Export and import".
#
# openssl enc can't do AES-GCM, so the script encrypts with AES-256-CTR and
# computes the GCM tag itself (NIST SP 800-38D). The salt has 8 bytes, the
# longest openssl enc takes; the format stores its length.

set -euo pipefail

ITERATIONS=600000  # MasterKey.DEFAULT_ITERATIONS

die() {
    echo "error: $*" >&2
    exit 1
}

hex_to_bin() {
    local hex=$1 format="" i
    for ((i = 0; i < ${#hex}; i += 2)); do
        format+="\\x${hex:i:2}"
    done
    printf "$format"
}

bin_to_hex() {
    od -An -tx1 -v | tr -d ' \n'
}

if [ $# -ne 2 ]; then
    echo "Usage: $0 <input.xml> <output.sq2>" >&2
    exit 2
fi
input=$1
output=$2

[ -f "$input" ] || die "$input not found"
grep -q '<passdroid' "$input" || die "$input has no <passdroid> element"
[ ! -e "$output" ] || die "$output exists"
command -v openssl > /dev/null || die "openssl not found"

read -rsp "Password for the export: " password; echo
[ -n "$password" ] || die "empty password"
read -rsp "Repeat the password: " repeat; echo
[ "$password" = "$repeat" ] || die "the passwords don't match"
unset repeat

# Key: PBKDF2-HMAC-SHA256(password, salt, iterations), 32 bytes. ECB has no
# IV, so openssl derives exactly the key.
salt=$(openssl rand -hex 8)
key=$(printf '%s\n' "$password" |
      openssl enc -aes-256-ecb -pbkdf2 -iter "$ITERATIONS" -md sha256 \
                  -S "$salt" -pass stdin -P | sed -n 's/^key=//p') || key=""
unset password
[ ${#key} -eq 64 ] || die "key derivation failed, openssl 1.1.1 or later is needed"

# Header "sq2", iterations (big endian), salt length, salt. It is the GCM
# additional data, and happens to be one block long.
header="737132$(printf '%08x%02x' "$ITERATIONS" $((${#salt} / 2)))$salt"
nonce=$(openssl rand -hex 12)

# Encrypt a zero block in front of the XML with the counter starting at
# nonce || 1: the first block is then E(K, J0) for the tag and the rest is
# the GCM ciphertext, whose counter starts at nonce || 2.
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
{ head -c 16 /dev/zero; cat "$input"; } |
    openssl enc -aes-256-ctr -K "$key" -iv "${nonce}00000001" > "$tmp"
size=$(($(wc -c < "$tmp") - 16))

# GHASH. Values in GF(2^128) are pairs of 64 bit integers (hi, lo); bash
# integers are 64 bit two's complement.
hash_key=$(head -c 16 /dev/zero | openssl enc -aes-256-ecb -K "$key" -nopad | bin_to_hex)
unset key
SIGN=$((-0x7FFFFFFFFFFFFFFF - 1))  # 1 << 63
LOW63=0x7FFFFFFFFFFFFFFF
R=$((-0x1F00000000000000))         # 0xE1 << 120, the reduction polynomial

# vh[i], vl[i]: hash key * x^i, i.e. the hash key times bit i of a block
vh0=$((16#${hash_key:0:16}))
vl0=$((16#${hash_key:16:16}))
for ((i = 0; i < 128; i++)); do
    vh[i]=$vh0
    vl[i]=$vl0
    carry=$((vl0 & 1))
    vl0=$(( ((vl0 >> 1) & LOW63) | ((vh0 & 1) ? SIGN : 0) ))
    vh0=$(( ((vh0 >> 1) & LOW63) ^ (carry ? R : 0) ))
done

# th[256 * j + b], tl[256 * j + b]: hash key * (byte j of a block = b, the
# other bytes 0). A block times the hash key is then the sum (XOR) of the
# entries for its 16 bytes.
bit=([1]=7 [2]=6 [4]=5 [8]=4 [16]=3 [32]=2 [64]=1 [128]=0)
for ((j = 0; j < 16; j++)); do
    th[256 * j]=0
    tl[256 * j]=0
    for ((b = 1; b < 256; b++)); do
        low=$((b & -b))
        i=$((8 * j + bit[low]))
        th[256 * j + b]=$(( th[256 * j + (b ^ low)] ^ vh[i] ))
        tl[256 * j + b]=$(( tl[256 * j + (b ^ low)] ^ vl[i] ))
    done
done

multiply_h=""
multiply_l=""
for ((j = 0; j < 8; j++)); do
    multiply_h+="^th[$((256 * j))+((xh>>$((56 - 8 * j)))&255)]^th[$((256 * (j + 8)))+((xl>>$((56 - 8 * j)))&255)]"
    multiply_l+="^tl[$((256 * j))+((xh>>$((56 - 8 * j)))&255)]^tl[$((256 * (j + 8)))+((xl>>$((56 - 8 * j)))&255)]"
done
multiply_h=${multiply_h:1}
multiply_l=${multiply_l:1}

yh=0
yl=0
ghash_block() {
    local block="${1}00000000000000000000000000000000"
    xh=$(( yh ^ 16#${block:0:16} ))
    xl=$(( yl ^ 16#${block:16:16} ))
    yh=$(( multiply_h ))
    yl=$(( multiply_l ))
}

ghash_block "$header"
# od writes 16 bytes per line, one block
mapfile -t blocks < <(od -An -tx1 -v -j 16 "$tmp" | tr -d ' ')
for block in "${blocks[@]}"; do
    [ -n "$block" ] && ghash_block "$block"
done
ghash_block "$(printf '%016x%016x' $((${#header} / 2 * 8)) $((size * 8)))"

ej0=$(head -c 16 "$tmp" | bin_to_hex)
tag=$(printf '%016x%016x' $(( yh ^ 16#${ej0:0:16} )) $(( yl ^ 16#${ej0:16:16} )))

{
    hex_to_bin "$header$nonce"
    tail -c +17 "$tmp"
    hex_to_bin "$tag"
} > "$output"

echo "Wrote $output. Import it in Passdroid++ with the password; the import"
echo "replaces all entries in the app. Delete $input when you no longer need it."
