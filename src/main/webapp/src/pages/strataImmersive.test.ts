// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import type { PartitionActivity, PartitionActivityResponse } from '../api/types';
import * as THREE from 'three';
import { partitionBucketReading, createPartitionRibbons } from './strataImmersive';

const p: PartitionActivity = {
  partition: 3, counts: [5, 0, 12, 6], coveredFromMs: 1100, available: true, note: null,
};
describe('measured partition ribbons', () => {
  it('distinguishes incomplete retained buckets from measured zeros', () => {
    expect(partitionBucketReading(p, 1000, 100, 0)).toBeNull();
    expect(partitionBucketReading(p, 1000, 100, 1)).toBe(0);
    expect(partitionBucketReading({ ...p, available: false }, 1000, 100, 1)).toBeNull();
  });
  it('builds bounded meshes only for measured activity', () => {
    const r: PartitionActivityResponse = {
      topic: 'orders', windowStartMs: 1000, windowEndMs: 1400, bucketMs: 100, buckets: 4,
      partitionsTotal: 1, partitionsMeasured: 1, truncated: false, partitions: [p], warnings: [],
    };
    const meshes = createPartitionRibbons(r, new THREE.Vector3(1, 0, 2), false);
    expect(meshes.children).toHaveLength(1);
    expect(meshes.children[0].userData.partition).toBe(3);
    expect((meshes.children[0] as THREE.Mesh).geometry.getAttribute('position').count).toBe(12);
  });
});
