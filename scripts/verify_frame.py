#!/usr/bin/env python3
"""Decodifica o padrão 'Quadros numerados' do NEURELIX numa imagem: marcador magenta + contador de 32 bits.
Uso: verify_frame.py imagem.jpg  → imprime 'marker=<bool> seq=<n>' e retorna 0 se o marcador estiver presente."""
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); W, H = im.size
def px(nx, ny_top): return im.getpixel((min(W-1, int(nx*W)), min(H-1, int(ny_top*H))))
m = px(0.047, 1-0.13)
marker = m[0] > 170 and m[1] < 100 and m[2] > 170
seq = 0
for i in range(32):
    c = px((4+i+0.5)/40, 1-0.08); seq = (seq << 1) | (1 if c[0] > 128 else 0)
print(f"size={W}x{H} marker={marker} seq={seq if marker else -1}")
sys.exit(0 if marker else 1)
