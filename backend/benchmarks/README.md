# 后端离线性能探针

这些探针使用固定合成数据，不访问线上 MySQL、Redis 或曲库。结果用于比较同一机器、
同一工具链下的代码改动，不代表整站吞吐量或生产内存降幅。探针与临时产物不打进服务端 JAR。

## 推荐排序

在仓库根目录运行，使用项目的 Java 27：

```bash
neko_bench_dir="$(mktemp -d)"
javac -d "$neko_bench_dir" \
  backend/src/main/java/com/neko/music/service/PlaylistRecommendationRanker.java \
  backend/benchmarks/BenchmarkMeter.java \
  backend/benchmarks/PlaylistRecommendationBenchmark.java
java -Xms128m -Xmx256m -cp "$neko_bench_dir" \
  com.neko.music.service.PlaylistRecommendationBenchmark
```

输入为 600 个歌单，每个包含 80 个歌曲 ID，用户具有 40 条搜索信号。先对个性化、游客、
低交互画像分别执行稳定推荐和刷新推荐，使用固定随机种子计算结果摘要；再预热并测量排序。

这次改造在相同探针下的结果：

| 每次排序 | 改造前 | 改造后 |
| --- | ---: | ---: |
| 耗时中位数 | 31.55 ms | 14.95 ms |
| 耗时 P95 | 33.42 ms | 16.03 ms |
| 线程 CPU 平均值 | 31.28 ms | 15.05 ms |
| 累计堆分配 | 94,819,743 字节 | 10,721,199 字节 |

24 组推荐结果的 SHA-256 摘要一致：
`cd90adf607066971b2009b72e38c5d3da63f96024fe5fad938f7387144d42748`。
摘要包含排序、分数、推荐理由与探索标记。分配量是一次计算中累计分配的字节数，
并非进程 RSS 或同时存活的对象大小。

## Rust 经典声纹匹配

在 Linux 仓库根目录运行：

```bash
rustc --edition=2021 -O backend/benchmarks/RecognitionMatchBenchmark.rs \
  -o /tmp/nekomusic-recognition-benchmark
/tmp/nekomusic-recognition-benchmark
```

探针直接编译当前 Rust 引擎，以 800 个查询特征分别覆盖有序、无序与较多候选歌曲的输入。
它报告匹配结果、耗时、线程 CPU、累计原生分配和分配次数。计费分配器本身也有开销，
比较时必须使用相同探针。

| 单次匹配累计原生分配 | 改造前 | 改造后 |
| --- | ---: | ---: |
| 有序输入，80 首候选曲目 | 137,868 字节 | 95,484 字节 |
| 无序输入，80 首候选曲目 | 144,268 字节 | 119,308 字节 |
| 无序输入，512 首候选曲目 | 245,580 字节 | 206,916 字节 |

三种输入的音乐 ID、对齐数、置信度、偏移和次优得分均与改造前一致。匹配耗时在该小规模
探针下变化较小，不据此宣称整条识曲链路获得相同比例的加速；该探针不包含音频解码、
频谱提取、和声匹配、HTTP 或数据库耗时。
