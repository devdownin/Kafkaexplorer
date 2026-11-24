// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import * as THREE from 'three';
import type { PartitionActivity, PartitionActivityResponse } from '../api/types';
import type { StrataProjection } from './strataForecast';

const BUCKET_HEIGHT = 0.43;
const BUCKET_OFFSET = 0.35;

/** Don't render an offset gap or a retention floor as a measured, idle ribbon. */
export function partitionBucketReading(
  series: PartitionActivity, windowStartMs: number, bucketMs: number, index: number,
): number | null {
  if (!series.available || index < 0 || index >= series.counts.length) return null;
  if (series.coveredFromMs !== null && windowStartMs + index * bucketMs < series.coveredFromMs) return null;
  const n = series.counts[index];
  return Number.isFinite(n) && n >= 0 ? n : null;
}

/**
 * A spatial comb of *measured* partition-production strips around the focused topic.
 * Each segment shows its own offset change: width varies with observed per-partition
 * rate, not with estimated lag or topic-level average.
 */
export function createPartitionRibbons(
  data: PartitionActivityResponse, center: THREE.Vector3, dark: boolean,
): THREE.Group {
  const root = new THREE.Group();
  root.position.copy(center);
  const readings = data.partitions.flatMap(series =>
    series.counts.map((_, i) => partitionBucketReading(series, data.windowStartMs, data.bucketMs, i))
      .filter((n): n is number => n !== null));
  const maxRate = Math.max(1, ...readings);
  const count = Math.max(1, data.partitions.length);
  data.partitions.forEach((series, i) => {
    const angle = 2 * Math.PI * i / count;
    const nx = Math.cos(angle), nz = Math.sin(angle);
    const tx = -nz, tz = nx;
    const points: number[] = [];
    const triangles: number[] = [];
    for (let bucket = 0; bucket < Math.min(12, data.buckets); bucket++) {
      const value = partitionBucketReading(series, data.windowStartMs, data.bucketMs, bucket);
      if (value === null) continue;
      const width = 0.05 + 0.23 * Math.log1p(value) / Math.log1p(maxRate);
      const radius = 1.25;
      const y0 = BUCKET_OFFSET + bucket * BUCKET_HEIGHT;
      const y1 = y0 + BUCKET_HEIGHT * 0.88;
      const leftX = radius * nx - width * tx;
      const leftZ = radius * nz - width * tz;
      const rightX = radius * nx + width * tx;
      const rightZ = radius * nz + width * tz;
      const index = points.length / 3;
      points.push(leftX, y0, leftZ, rightX, y0, rightZ,
        rightX, y1, rightZ, leftX, y1, leftZ);
      triangles.push(index, index + 1, index + 2, index, index + 2, index + 3);
    }
    if (!points.length) return;
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(points, 3));
    geometry.setIndex(triangles);
    geometry.computeVertexNormals();
    const hue = (i * 0.61803398875) % 1;
    const color = new THREE.Color().setHSL((0.47 + hue * 0.32) % 1, dark ? 0.74 : 0.66, dark ? 0.66 : 0.44);
    const mesh = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial({
      color, side: THREE.DoubleSide, transparent: true, opacity: 0.82, depthWrite: false,
    }));
    mesh.userData.partition = series.partition;
    root.add(mesh);
  });
  return root;
}

function chartLine(points: THREE.Vector3[], color: number, width = 0.027): THREE.Mesh | null {
  if (points.length < 2) return null;
  const curve = new THREE.CatmullRomCurve3(points);
  return new THREE.Mesh(
    new THREE.TubeGeometry(curve, Math.max(12, points.length * 4), width, 6, false),
    new THREE.MeshBasicMaterial({ color }),
  );
}

/**
 * A text billboard drawn on a 2D canvas. Returns null where no 2D context exists (jsdom, a
 * locked-down browser): the scene then simply carries no text, the HTML panels keep the figures.
 */
export function textSprite(text: string, dark: boolean, width = 2.6): THREE.Sprite | null {
  const canvas = document.createElement('canvas');
  canvas.width = 320;
  canvas.height = 64;
  const context = canvas.getContext('2d');
  if (!context) return null;
  context.fillStyle = dark ? 'rgba(14, 30, 48, .9)' : 'rgba(249, 252, 255, .92)';
  context.fillRect(0, 0, 320, 64);
  context.font = '600 26px system-ui, sans-serif';
  context.fillStyle = dark ? '#E4ECF7' : '#1D334B';
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  context.fillText(text, 160, 33, 304);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: texture, transparent: true, depthTest: false, depthWrite: false,
  }));
  sprite.scale.set(width, width / 5, 1);
  return sprite;
}

/** Three significant digits: an axis label, not a measurement readout. */
export function axisValue(value: number, unit: string): string {
  const figure = Number.isFinite(value) ? value.toLocaleString(undefined, { maximumSignificantDigits: 3 }) : '—';
  return unit ? `${figure} ${unit}` : figure;
}

/**
 * A separate metric axis, not a continuation of offset-production rings. Its top and bottom are
 * labelled with their values and the metric's unit, and the two halves are named: without
 * them the canopy was a shape whose height meant nothing that could be read.
 */
export function createForecastCanopy(
  forecast: StrataProjection, position: THREE.Vector3, dark: boolean,
): THREE.Group {
  const root = new THREE.Group();
  root.position.set(position.x + 2.1, 0, position.z + 2.1);
  const values = [
    ...forecast.observed.map(p => p.value),
    ...forecast.future.flatMap(p => [p.q10, p.q50, p.q90]),
  ];
  const min = Math.min(...values);
  const span = Math.max(1e-9, Math.max(...values) - min);
  const fy = (value: number) => 0.75 + (value - min) / span * 4.45;
  const historyCount = forecast.observed.length;
  const predCount = forecast.future.length;
  const hx = (i: number) => i / Math.max(1, historyCount - 1) * 2.1;
  const fx = (i: number) => 2.25 + (i + 1) / Math.max(1, predCount) * 3.1;
  const observedPoints = forecast.observed.map((p, i) =>
    new THREE.Vector3(hx(i), fy(p.value), 0));
  const predictionPoints = forecast.future.map((p, i) =>
    new THREE.Vector3(fx(i), fy(p.q50), 0));
  const measured = chartLine(observedPoints, dark ? 0x4de0d1 : 0x087f84, 0.038);
  if (measured) root.add(measured);
  const prediction = chartLine(predictionPoints, dark ? 0xbda9ff : 0x8167ce, 0.043);
  if (prediction) root.add(prediction);
  const points: number[] = [];
  const indices: number[] = [];
  forecast.future.forEach((p, i) => {
    const x = fx(i);
    points.push(x, fy(p.q10), 0.02, x, fy(p.q90), 0.02);
    if (i) {
      const j = 2 * i;
      indices.push(j - 2, j - 1, j, j, j - 1, j + 1);
    }
  });
  if (indices.length > 0) {
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(points, 3));
    geometry.setIndex(indices);
    const band = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial({
      color: dark ? 0x9a88ee : 0x7a6bbd, transparent: true, opacity: 0.25,
      side: THREE.DoubleSide, depthWrite: false,
    }));
    root.add(band);
  }
  const boundary = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(2.19, 0.4, 0), new THREE.Vector3(2.19, 5.5, 0)]),
    new THREE.LineDashedMaterial({ color: dark ? 0x98abcb : 0x61728b,
      transparent: true, opacity: 0.55, dashSize: 0.18, gapSize: 0.12 }));
  boundary.computeLineDistances();
  root.add(boundary);
  const labels: Array<[string, number, number, number]> = [
    [axisValue(min + span, forecast.unit), -0.9, fy(min + span), 2.2],
    [axisValue(min, forecast.unit), -0.9, fy(min), 2.2],
    ['Measured', 1.05, 5.75, 1.8],
    ['Forecast q10–q90', 3.8, 5.75, 2.6],
  ];
  for (const [text, x, y, width] of labels) {
    const sprite = textSprite(text, dark, width);
    if (!sprite) continue;
    sprite.position.set(x, y, 0);
    root.add(sprite);
  }
  return root;
}
