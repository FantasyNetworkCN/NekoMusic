// Linux 独立匹配探针：rustc --edition=2021 -O backend/benchmarks/RecognitionMatchBenchmark.rs -o /tmp/neko-match-bench
// 使用相同源码分别链接改动前后的引擎，比较结果摘要、线程 CPU、耗时与原生分配；不依赖线上曲库。
include!("../native/recognition/src/lib.rs");

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

struct CountingAllocator;
static ALLOCATED: AtomicU64 = AtomicU64::new(0);
static ALLOCATIONS: AtomicU64 = AtomicU64::new(0);

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATED.fetch_add(layout.size() as u64, Ordering::Relaxed);
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        System.alloc(layout)
    }
    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        System.dealloc(pointer, layout);
    }
    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        ALLOCATED.fetch_add(size as u64, Ordering::Relaxed);
        ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        System.realloc(pointer, layout, size)
    }
}

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

#[repr(C)]
struct ThreadTime {
    seconds: i64,
    nanos: i64,
}
extern "C" {
    fn clock_gettime(clock: i32, time: *mut ThreadTime) -> i32;
}
fn cpu_nanos() -> i64 {
    let mut time = ThreadTime { seconds: 0, nanos: 0 };
    assert_eq!(unsafe { clock_gettime(3, &mut time) }, 0);
    time.seconds * 1_000_000_000 + time.nanos
}

fn fixture(tracks: usize, points: usize, shuffled: bool) -> (Index, Vec<u64>) {
    let mut postings = Vec::new();
    let mut rows = Vec::new();
    let mut query = Vec::new();
    for i in 0..points {
        let hash = (i as u64 * 0x9e3779b9) & ((1 << 34) - 1);
        let offset = postings.len();
        postings.push((1 << 32) | (i as u64 * 4 + 1000));
        for track in 2..=tracks {
            if (i + track) % (track + 1) == 0 {
                postings.push(((track as u64) << 32) | (i as u64 * 4 + track as u64 * 17));
            }
        }
        rows.push((hash, offset, postings.len() - offset));
        query.extend([hash, i as u64 * 4]);
    }
    let mut index = Index::new(points, postings.len(), None).unwrap();
    index.postings.copy_from_slice(&postings);
    for (hash, offset, length) in rows {
        let mut at = bucket(hash) & (index.slots.len() - 1);
        while index.slots[at].length != 0 {
            at = (at + 1) & (index.slots.len() - 1);
        }
        index.slots[at] = Slot { hash, offset: offset as u32, length: length as u32 };
    }
    if shuffled {
        let mut random = 7_u64;
        for i in (1..points).rev() {
            random = random.wrapping_mul(6364136223846793005).wrapping_add(1);
            let j = random as usize % (i + 1);
            query.swap(i * 2, j * 2);
            query.swap(i * 2 + 1, j * 2 + 1);
        }
    }
    (index, query)
}

fn answer(index: &Index, query: &[u64], tracks: usize) -> Option<Match> {
    find(index, query, 128_000, tracks as i32, 6, 0.08)
}

fn main() {
    for (name, tracks, shuffled) in [
        ("ordered", 80, false),
        ("shuffled", 80, true),
        ("many_candidates", 512, true),
    ] {
        let (index, query) = fixture(tracks, 800, shuffled);
        let expected = answer(&index, &query, tracks).unwrap();
        assert_eq!(expected.music_id, 1);
        for _ in 0..100 {
            std::hint::black_box(answer(&index, &query, tracks));
        }
        let mut elapsed = [0.0_f64; 300];
        let allocated = ALLOCATED.load(Ordering::Relaxed);
        let allocations = ALLOCATIONS.load(Ordering::Relaxed);
        let cpu = cpu_nanos();
        for sample in &mut elapsed {
            let start = Instant::now();
            let actual = std::hint::black_box(answer(&index, &query, tracks));
            *sample = start.elapsed().as_secs_f64() * 1_000_000.0;
            assert_eq!(actual, Some(expected));
        }
        let cpu = (cpu_nanos() - cpu) as f64 / 1000.0 / elapsed.len() as f64;
        let bytes = (ALLOCATED.load(Ordering::Relaxed) - allocated) / elapsed.len() as u64;
        let calls = (ALLOCATIONS.load(Ordering::Relaxed) - allocations) / elapsed.len() as u64;
        elapsed.sort_by(f64::total_cmp);
        println!("{name} median_us={:.3} p95_us={:.3} cpu_us={cpu:.3} allocated_bytes={bytes} allocations={calls} match={expected:?}",
            elapsed[150], elapsed[284]);
    }
}
