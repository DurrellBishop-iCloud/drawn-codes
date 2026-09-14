// Minimal 3MF writer: a zip (deflate via CompressionStream) containing a
// 3D/3dmodel.model XML with one coloured object per layer. Slicers
// (Bambu, Prusa, Orca, Cura) open it as a multi-part object with the
// part colours already assigned — the Fusion-style single-file export.

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(data) {
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) {
    c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

async function deflateRaw(data) {
  const cs = new CompressionStream('deflate-raw');
  const stream = new Blob([data]).stream().pipeThrough(cs);
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** Store entries [{name, text}] into a zip (deflated). */
export async function makeZip(entries) {
  const enc = new TextEncoder();
  const parts = [];
  const central = [];
  let offset = 0;
  for (const { name, text } of entries) {
    const nameB = enc.encode(name);
    const raw = enc.encode(text);
    const comp = await deflateRaw(raw);
    const crc = crc32(raw);
    const head = new DataView(new ArrayBuffer(30));
    head.setUint32(0, 0x04034b50, true);
    head.setUint16(4, 20, true);        // version needed
    head.setUint16(8, 8, true);         // deflate
    head.setUint32(14, crc, true);
    head.setUint32(18, comp.length, true);
    head.setUint32(22, raw.length, true);
    head.setUint16(26, nameB.length, true);
    parts.push(new Uint8Array(head.buffer), nameB, comp);

    const cd = new DataView(new ArrayBuffer(46));
    cd.setUint32(0, 0x02014b50, true);
    cd.setUint16(4, 20, true);
    cd.setUint16(6, 20, true);
    cd.setUint16(10, 8, true);
    cd.setUint32(16, crc, true);
    cd.setUint32(20, comp.length, true);
    cd.setUint32(24, raw.length, true);
    cd.setUint16(28, nameB.length, true);
    cd.setUint32(42, offset, true);
    central.push(new Uint8Array(cd.buffer), nameB);
    offset += 30 + nameB.length + comp.length;
  }
  const cdSize = central.reduce((a, b) => a + b.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, entries.length, true);
  end.setUint16(10, entries.length, true);
  end.setUint32(12, cdSize, true);
  end.setUint32(16, offset, true);
  return new Blob([...parts, ...central, new Uint8Array(end.buffer)],
    { type: 'model/3mf' });
}

/**
 * Build a 3MF blob from parts: [{ name, color '#rrggbb', mesh (THREE.Mesh,
 * world matrices up to date) }]. Units: millimetres.
 */
export async function make3MF(parts, THREE) {
  let objectsXml = '';
  let basesXml = '';
  let itemsXml = '';
  const v = new THREE.Vector3();
  parts.forEach((part, k) => {
    basesXml += `<base name="${part.name}" displaycolor="${part.color}FF"/>`;
    const pos = part.mesh.geometry.getAttribute('position');
    const verts = [];
    const index = new Map();
    const tris = [];
    for (let i = 0; i < pos.count; i++) {
      v.fromBufferAttribute(pos, i).applyMatrix4(part.mesh.matrixWorld);
      const key = v.x.toFixed(4) + ',' + v.y.toFixed(4) + ',' + v.z.toFixed(4);
      let idx = index.get(key);
      if (idx === undefined) {
        idx = verts.length;
        verts.push(`<vertex x="${v.x.toFixed(4)}" y="${v.y.toFixed(4)}" z="${v.z.toFixed(4)}"/>`);
        index.set(key, idx);
      }
      tris.push(idx);
    }
    let triXml = '';
    for (let i = 0; i < tris.length; i += 3) {
      triXml += `<triangle v1="${tris[i]}" v2="${tris[i + 1]}" v3="${tris[i + 2]}"/>`;
    }
    const oid = k + 2;
    objectsXml += `<object id="${oid}" type="model" pid="1" pindex="${k}">` +
      `<mesh><vertices>${verts.join('')}</vertices>` +
      `<triangles>${triXml}</triangles></mesh></object>`;
    itemsXml += `<item objectid="${oid}"/>`;
  });

  const model = `<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:m="http://schemas.microsoft.com/3dmanufacturing/material/2015/02">
<resources><basematerials id="1">${basesXml}</basematerials>${objectsXml}</resources>
<build>${itemsXml}</build>
</model>`;

  return makeZip([
    { name: '[Content_Types].xml', text:
      '<?xml version="1.0" encoding="UTF-8"?>' +
      '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
      '<Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>' +
      '</Types>' },
    { name: '_rels/.rels', text:
      '<?xml version="1.0" encoding="UTF-8"?>' +
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Target="/3D/3dmodel.model" Id="rel-1" ' +
      'Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>' +
      '</Relationships>' },
    { name: '3D/3dmodel.model', text: model },
  ]);
}
