// SPDX-License-Identifier: AGPL-3.0-or-later
// 匹配语义源自项目的 Panako 派生实现，保留原阈值、稳定排序及时间对齐表达式。
use std::collections::{HashMap, HashSet};
use std::hash::{BuildHasherDefault, Hasher};
use std::mem::size_of;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

const MAX_COMPARISONS: usize = 8_000_000;
const HOP_SIZE: f64 = 128.0;
const SAMPLE_RATE: f64 = 16_000.0;

/// 哈希仅来自已提取的内部数值特征，使用混合全部位的整数散列。
#[derive(Default)]
struct FeatureHasher(u64);
impl Hasher for FeatureHasher {
    fn finish(&self) -> u64 {
        self.0
    }
    fn write(&mut self, bytes: &[u8]) {
        for byte in bytes {
            self.write_u64(*byte as u64);
        }
    }
    fn write_u64(&mut self, value: u64) {
        let mut mixed = value ^ self.0.wrapping_mul(0x9e3779b97f4a7c15);
        mixed = (mixed ^ (mixed >> 33)).wrapping_mul(0xff51afd7ed558ccd);
        mixed = (mixed ^ (mixed >> 33)).wrapping_mul(0xc4ceb9fe1a85ec53);
        self.0 = mixed ^ (mixed >> 33);
    }
    fn write_i32(&mut self, value: i32) {
        self.write_u64(value as u64);
    }
}
type FeatureBuildHasher = BuildHasherDefault<FeatureHasher>;

#[repr(C)]
#[derive(Clone, Copy, Default, Debug)]
struct Slot {
    hash: u64,
    offset: u32,
    length: u32,
}

#[derive(Default)]
pub struct Index {
    slots: Box<[Slot]>,
    postings: Box<[u64]>,
    entries: usize,
    total_postings: u64,
    base: Option<Arc<Index>>,
}

fn bucket(mut key: u64) -> usize {
    key = (key ^ (key >> 33)).wrapping_mul(0xff51afd7ed558ccd);
    key = (key ^ (key >> 33)).wrapping_mul(0xc4ceb9fe1a85ec53);
    (key ^ (key >> 33)) as usize
}

impl Index {
    fn new(entries: usize, postings: usize, base: Option<Arc<Index>>) -> Option<Self> {
        if postings > u32::MAX as usize {
            return None;
        }
        let capacity = entries
            .checked_mul(4)?
            .checked_div(3)?
            .checked_add(1)?
            .checked_next_power_of_two()?;
        let mut slots = Vec::new();
        slots.try_reserve_exact(capacity).ok()?;
        slots.resize(capacity, Slot::default());
        let mut values = Vec::new();
        values.try_reserve_exact(postings).ok()?;
        values.resize(postings, 0);
        Some(Self {
            slots: slots.into_boxed_slice(),
            postings: values.into_boxed_slice(),
            entries: base.as_ref().map_or(0, |index| index.entries),
            total_postings: base.as_ref().map_or(0, |index| index.total_postings),
            base,
        })
    }

    fn own(&self, hash: u64) -> Option<&[u64]> {
        let mask = self.slots.len() - 1;
        let mut at = bucket(hash) & mask;
        loop {
            let slot = &self.slots[at];
            if slot.length == 0 {
                return None;
            }
            if slot.hash == hash {
                return Some(
                    &self.postings
                        [slot.offset as usize..slot.offset as usize + slot.length as usize],
                );
            }
            at = (at + 1) & mask;
        }
    }

    fn get(&self, hash: u64) -> Option<&[u64]> {
        self.own(hash)
            .or_else(|| self.base.as_ref().and_then(|base| base.get(hash)))
    }

    #[cfg(test)]
    fn insert(&mut self, hash: u64, offset: usize, length: usize) -> bool {
        if length == 0
            || length > u32::MAX as usize
            || offset
                .checked_add(length)
                .is_none_or(|end| end > self.postings.len())
        {
            return false;
        }
        let mask = self.slots.len() - 1;
        let mut at = bucket(hash) & mask;
        for _ in 0..self.slots.len() {
            if self.slots[at].length == 0 {
                let previous = self.base.as_ref().and_then(|base| base.get(hash));
                self.total_postings += length as u64;
                if let Some(previous) = previous {
                    self.total_postings -= previous.len() as u64;
                } else {
                    self.entries += 1;
                }
                self.slots[at] = Slot {
                    hash,
                    offset: offset as u32,
                    length: length as u32,
                };
                return true;
            }
            if self.slots[at].hash == hash {
                return false;
            }
            at = (at + 1) & mask;
        }
        false
    }

    fn bytes(&self) -> usize {
        size_of::<Self>()
            + 2 * size_of::<usize>()
            + self.slots.len() * size_of::<Slot>()
            + self.postings.len() * 8
            + self.base.as_ref().map_or(0, |base| base.bytes())
    }
}

#[repr(C)]
#[derive(Clone, Copy, Default, Debug, PartialEq)]
pub struct Match {
    music_id: i32,
    aligned: i32,
    confidence: f64,
    offset: i32,
    runner_up: i32,
}

fn median(values: &mut [f64]) -> f64 {
    // 输入是有限的整数差和有限斜率，不包含 NaN。
    let middle = values.len() / 2;
    let even = values.len() % 2 == 0;
    let (lower, center, _) =
        values.select_nth_unstable_by(middle, |a, b| a.partial_cmp(b).unwrap());
    if even {
        let previous = lower.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        (previous + *center) / 2.0
    } else {
        *center
    }
}

fn query_time(hit: u64) -> i32 {
    (hit >> 32) as i32
}
fn delta(hit: u64) -> i32 {
    (hit as i32).wrapping_sub(query_time(hit))
}

fn alignment(hits: &[u64]) -> (f64, f64) {
    let mut work = vec![0.0; hits.len()];
    let mut slope = 0.0;
    if hits.len() >= 4 {
        let part = 2.max(hits.len() / 5);
        let first = &hits[..part];
        let last = &hits[hits.len() - part..];
        let average = |values: &[u64]| {
            values
                .iter()
                .fold(0.0, |sum, &hit| sum + query_time(hit) as f64)
                / values.len() as f64
        };
        let x1 = average(first);
        let x2 = average(last);
        for (target, &hit) in work.iter_mut().zip(first) {
            *target = delta(hit) as f64;
        }
        let y1 = median(&mut work[..part]);
        for (target, &hit) in work.iter_mut().zip(last) {
            *target = delta(hit) as f64;
        }
        let y2 = median(&mut work[..part]);
        if (x2 - x1).abs() > 1.0 {
            slope = (y2 - y1) / (x2 - x1);
            if !slope.is_finite() || slope.abs() > 0.35 {
                slope = 0.0;
            }
        }
    }
    for (target, &hit) in work.iter_mut().zip(hits) {
        *target = delta(hit) as f64 - slope * query_time(hit) as f64;
    }
    (slope, median(&mut work))
}

fn find(
    index: &Index,
    query: &[u64],
    sample_count: i64,
    music_count: i32,
    minimum: i32,
    minimum_confidence: f64,
) -> Option<Match> {
    let mut by_music: HashMap<i32, Vec<u64>, FeatureBuildHasher> = HashMap::default();
    let mut unique =
        HashSet::with_capacity_and_hasher(query.len() / 2, FeatureBuildHasher::default());
    let mut comparable = 0;
    let mut comparisons = 0;
    let maximum = 512.max(music_count.wrapping_mul(64)) as usize;
    for point in query.chunks_exact(2) {
        let hash = point[0];
        let time = point[1] as i32;
        if !unique.insert((hash, time)) {
            continue;
        }
        let mut any = false;
        // 与 Java 相同，邻近范围按 signed long 判断是否发生溢出。
        let start = (hash as i64).wrapping_sub(2);
        let end = (hash as i64).wrapping_add(2);
        if start <= end {
            for candidate in start..=end {
                let Some(postings) = index.get(candidate as u64) else {
                    continue;
                };
                if postings.is_empty() || postings.len() > maximum {
                    continue;
                }
                any = true;
                for &posting in postings {
                    by_music
                        .entry((posting >> 32) as i32)
                        .or_default()
                        .push(((time as u64) << 32) | (posting & 0xffff_ffff));
                    comparisons += 1;
                    if comparisons >= MAX_COMPARISONS {
                        break;
                    }
                }
                if comparisons >= MAX_COMPARISONS {
                    break;
                }
            }
        }
        if any {
            comparable += 1;
        }
        if comparisons >= MAX_COMPARISONS {
            break;
        }
    }
    if comparable == 0 {
        return None;
    }
    let short = (sample_count as f64 / SAMPLE_RATE) < 6.0;
    let minimum = if short { 3 } else { 3.max(minimum) };
    let confidence_threshold = if short {
        minimum_confidence.min(0.03)
    } else {
        minimum_confidence
    };
    let mut candidates = Vec::new();
    let mut ordered_music: Vec<_> = by_music.into_iter().collect();
    ordered_music.sort_unstable_by_key(|(music, _)| *music);
    for (music, mut hits) in ordered_music {
        if hits.len() < minimum as usize {
            continue;
        }
        if hits
            .windows(2)
            .any(|pair| query_time(pair[0]) > query_time(pair[1]))
        {
            hits.sort_by_key(|&hit| query_time(hit));
        }
        let (slope, intercept) = alignment(&hits);
        let mut aligned = 0;
        let mut first = i32::MAX;
        let mut last = i32::MIN;
        for hit in hits {
            let time = query_time(hit);
            if (delta(hit) as f64 - (slope * time as f64 + intercept)).abs() <= 2.5 {
                aligned += 1;
                first = first.min(time);
                last = last.max(time);
            }
        }
        if aligned < minimum
            || (last.wrapping_sub(first).wrapping_mul(HOP_SIZE as i32) as f64 / SAMPLE_RATE) < 1.0
        {
            continue;
        }
        let confidence = (aligned as f64 / comparable as f64).min(1.0);
        if confidence < confidence_threshold {
            continue;
        }
        candidates.push(Match {
            music_id: music,
            aligned,
            confidence,
            offset: ((intercept + 0.5).floor() as i64 as i32).max(0),
            runner_up: 0,
        });
    }
    candidates.sort_by_key(|candidate| std::cmp::Reverse(candidate.aligned));
    let mut best = *candidates.first()?;
    let runner = candidates.get(1).map_or(0, |candidate| candidate.aligned);
    let gap = if short { 1 } else { 3 };
    if runner != 0
        && best.aligned < runner + gap
        && (best.aligned as f64) < (runner as f64 * 1.10).ceil()
    {
        return None;
    }
    best.runner_up = runner;
    Some(best)
}

// ABI 只接收受信 Java 桥接层的参数；不可变 Arc 索引支持并发查询。
// 所有可能分配或 panic 的入口都截获 unwind，不允许异常穿过 C ABI。
/// # Safety
/// base 必须为空或仍存活的 Arc 原始指针；返回指针须且仅须释放一次。
#[no_mangle]
pub unsafe extern "C" fn neko_index_new(
    entries: u64,
    postings: u64,
    base: *const Index,
) -> *const Index {
    catch_unwind(AssertUnwindSafe(|| {
        let base = if base.is_null() {
            None
        } else {
            Arc::increment_strong_count(base);
            Some(Arc::from_raw(base))
        };
        Index::new(entries.try_into().ok()?, postings.try_into().ok()?, base)
            .map(|index| Arc::into_raw(Arc::new(index)))
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null())
}

/// # Safety
/// index 必须存活；返回内存仅允许在 seal 前由唯一构建线程写入。
#[no_mangle]
pub unsafe extern "C" fn neko_index_values(index: *const Index) -> *mut u64 {
    let mut owner = std::mem::ManuallyDrop::new(Arc::from_raw(index));
    Arc::get_mut(&mut owner)
        .map(|index| index.postings.as_mut_ptr())
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// index 必须已封装且存活，output 指向可写的 u64；返回切片不得超出 index 的生命周期。
#[no_mangle]
pub unsafe extern "C" fn neko_index_get(
    index: *const Index,
    hash: u64,
    output: *mut u64,
) -> *const u64 {
    match (*index).get(hash) {
        Some(values) => {
            *output = values.len() as u64;
            values.as_ptr()
        }
        None => {
            *output = 0;
            std::ptr::null()
        }
    }
}

/// # Safety
/// index 必须存活；返回内存仅允许在 seal 前由唯一构建线程写入合法 Slot。
#[no_mangle]
pub unsafe extern "C" fn neko_index_slots(index: *const Index) -> *mut u8 {
    let mut owner = std::mem::ManuallyDrop::new(Arc::from_raw(index));
    Arc::get_mut(&mut owner)
        .map(|index| index.slots.as_mut_ptr() as *mut u8)
        .unwrap_or(std::ptr::null_mut())
}
/// # Safety
/// index 必须指向仍存活的 Index。
#[no_mangle]
pub unsafe extern "C" fn neko_index_capacity(index: *const Index) -> u64 {
    let index = &*index;
    index.slots.len() as u64
}
/// # Safety
/// index 必须已封装且存活。
#[no_mangle]
pub unsafe extern "C" fn neko_index_size(index: *const Index) -> u64 {
    (*index).entries as u64
}
/// # Safety
/// index 必须已封装且存活。
#[no_mangle]
pub unsafe extern "C" fn neko_index_count(index: *const Index) -> u64 {
    (*index).total_postings
}
/// # Safety
/// index 必须指向仍存活的 Index。
#[no_mangle]
pub unsafe extern "C" fn neko_index_bytes(index: *const Index) -> u64 {
    (*index).bytes() as u64
}
/// # Safety
/// index 必须为空或由 new 返回且尚未释放的指针，不得与使用同一指针的调用并发。
#[no_mangle]
pub unsafe extern "C" fn neko_index_drop(index: *const Index) {
    if !index.is_null() {
        drop(Arc::from_raw(index));
    }
}

/// # Safety
/// index 必须为唯一拥有且尚未发布的索引；桥接层须写好不重复、可寻址的目录与 posting。
#[no_mangle]
pub unsafe extern "C" fn neko_index_seal(index: *const Index, expected: u64) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let mut owner = std::mem::ManuallyDrop::new(Arc::from_raw(index));
        let Some(index) = Arc::get_mut(&mut owner) else {
            return -1;
        };
        let mut entries = index.base.as_ref().map_or(0, |base| base.entries);
        let mut total = index.base.as_ref().map_or(0, |base| base.total_postings);
        let mut own_entries = 0;
        for slot in &index.slots {
            if slot.length == 0 {
                continue;
            }
            if slot.offset as u64 + slot.length as u64 > index.postings.len() as u64 {
                return -1;
            }
            own_entries += 1;
            if let Some(previous) = index.base.as_ref().and_then(|base| base.get(slot.hash)) {
                total -= previous.len() as u64;
            } else {
                entries += 1;
            }
            total += slot.length as u64;
        }
        if own_entries != expected {
            return -1;
        }
        index.entries = entries;
        index.total_postings = total;
        0
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub extern "C" fn neko_index_abi() -> i32 {
    1
}

/// # Safety
/// index 必须已封装且存活；query 包含 points * 2 个 u64，时间必须为非负 i32；
/// output 指向可写 Match。调用期间所有输入保持只读且存活。
#[no_mangle]
pub unsafe extern "C" fn neko_index_find(
    index: *const Index,
    query: *const u64,
    points: u64,
    samples: i64,
    music_count: i32,
    minimum: i32,
    confidence: f64,
    output: *mut Match,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        if index.is_null()
            || output.is_null()
            || points > (isize::MAX as u64 / 16)
            || (query.is_null() && points != 0)
        {
            return -1;
        }
        let query = if points == 0 {
            &[]
        } else {
            std::slice::from_raw_parts(query, points as usize * 2)
        };
        match find(&*index, query, samples, music_count, minimum, confidence) {
            Some(found) => {
                *output = found;
                1
            }
            None => 0,
        }
    }))
    .unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn indexes_full_hashes_and_layers_with_shared_base() {
        let mut base = Index::new(3, 3, None).unwrap();
        base.postings.copy_from_slice(&[11, 22, 33]);
        assert!(base.insert(0, 0, 1));
        assert!(base.insert(u64::MAX, 1, 1));
        assert!(base.insert(1 << 56, 2, 1));
        assert_eq!(base.get(0), Some(&[11][..]));
        let base = Arc::new(base);
        let mut next = Index::new(2, 3, Some(base.clone())).unwrap();
        next.postings.copy_from_slice(&[44, 55, 66]);
        assert!(next.insert(0, 0, 2));
        assert!(next.insert(2 << 56, 2, 1));
        assert_eq!(next.entries, 4);
        assert_eq!(next.total_postings, 5);
        assert_eq!(next.get(0), Some(&[44, 55][..]));
        assert_eq!(next.get(u64::MAX), Some(&[22][..]));
        assert_eq!(base.get(0), Some(&[11][..]));
        assert!(!next.insert(0, 0, 1));
        assert!(!next.insert(9, 9, 1));
    }

    #[test]
    fn finds_duplicate_unordered_full_width_query() {
        let mut index = Index::new(12, 12, None).unwrap();
        let mut query = Vec::new();
        for i in 0..6 {
            for high in [0, 1 << 40] {
                let hash = high + 10_000 + i * 100;
                let time = i * 60;
                let offset = query.len() / 4;
                index.postings[offset] = (7 << 32) | (time + 500);
                assert!(index.insert(hash, offset, 1));
                query.extend([hash, time, hash, time]);
            }
        }
        let points: Vec<_> = query
            .chunks_exact(2)
            .rev()
            .flat_map(|point| [point[0], point[1]])
            .collect();
        assert_eq!(
            find(&index, &points, 128_000, 1, 3, 0.05),
            Some(Match {
                music_id: 7,
                aligned: 12,
                confidence: 1.0,
                offset: 500,
                runner_up: 0,
            })
        );
        assert_eq!(find(&index, &[987654321, 0], 128_000, 1, 3, 0.05), None);
    }
}
