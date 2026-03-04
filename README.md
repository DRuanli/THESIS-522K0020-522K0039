# PTK-HUIM: Probabilistic Top-K High Utility Itemset Mining

[![Language](https://img.shields.io/badge/Language-Java_8+-orange.svg)](https://www.java.com/)
[![License](https://img.shields.io/badge/License-Academic-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-Research-green.svg)](README.md)

> **A high-performance tool for discovering the most valuable item combinations from uncertain transaction data.**

---

## Table of Contents

### For End Users
1. [What Is This?](#what-is-this)
2. [Quick Start](#quick-start)
3. [Installation](#installation)
4. [How to Use](#how-to-use)
5. [Input Data Format](#input-data-format)
6. [Understanding Results](#understanding-results)
7. [Performance Tuning](#performance-tuning)
8. [Troubleshooting](#troubleshooting)

### For Programmers
9. [Architecture Overview](#architecture-overview)
10. [Project Structure](#project-structure)
11. [Core Components](#core-components)
12. [Extending the System](#extending-the-system)
13. [Build and Test](#build-and-test)
14. [Configuration Options](#configuration-options)

---

# For End Users

## What Is This?

PTK-HUIM helps you discover the **K most valuable item combinations** from transaction data where items have:
- **Uncertainty**: Each item has a probability of actually being present (e.g., sensor readings with confidence, probabilistic purchases)
- **Value**: Each item has a profit or cost associated with it
- **Quantity**: Items can appear multiple times in a transaction

### Real-World Use Cases

- **Market Basket Analysis**: Find profitable product bundles when customer purchases are predicted with confidence scores
- **Sensor Networks**: Discover valuable patterns in noisy sensor data with reliability measures
- **Medical Diagnosis**: Identify symptom combinations with high diagnostic value considering certainty levels
- **Network Traffic**: Analyze packet patterns with sampling probabilities

### What You Get

Input your data, and PTK-HUIM will give you:
- **Top-K patterns** ranked by expected profit (considering probability and value)
- **Reliability scores** for each pattern (how likely it is to actually occur)
- **Performance metrics** (execution time, memory usage)

No need to guess thresholds—just specify how many patterns (K) you want.

---

## Quick Start

```bash
# Compile the project
find src -name "*.java" | xargs javac -d bin

# Run on sample data
java -cp bin cli.CommandLineInterface \
  data/chess_database.txt \
  data/chess_profits.txt \
  100 \
  0.1

# Arguments: <database> <profits> <k> <min_probability>
```

**What this does**: Finds the top 100 valuable item combinations from chess database, requiring each pattern to have at least 10% probability of occurrence.

---

## Installation

### Prerequisites

- Java 8 or higher
- At least 4GB RAM (8GB+ recommended for large datasets)
- Multi-core processor (optional, for parallel execution)

### Compile

```bash
# Navigate to project directory
cd /path/to/Thesis

# Compile all Java files
find src -name "*.java" | xargs javac -d bin -cp "src"
```

### Verify Installation

```bash
# Should display help message
java -cp bin cli.CommandLineInterface --help
```

---

## How to Use

### Basic Usage

```bash
java -cp bin cli.CommandLineInterface <database_file> <profit_file> <k> <min_probability> [OPTIONS]
```

**Required Arguments:**
- `database_file`: Path to transaction database (see format below)
- `profit_file`: Path to item profit table (see format below)
- `k`: Number of top patterns to find (e.g., 100)
- `min_probability`: Minimum reliability threshold 0-1 (e.g., 0.1 means 10%)

**Optional Flags:**
- `--help, -h`: Show help message
- `--debug`: Display detailed execution info with timing
- `--output, -o <file>`: Save results to file instead of console
- `--no-parallel`: Disable parallelization (for debugging)
- `--strategy <type>`: Search strategy (DFS, BEST_FIRST, BREADTH_FIRST, IDDFS)
- `--collector <type>`: Pattern collector (BASELINE, SHARDED, LAZY)
- `--join <type>`: Join algorithm (TWO_POINTER, EXPONENTIAL_SEARCH, BINARY_SEARCH)

### Examples

**Find top 1000 patterns with 70% minimum reliability:**
```bash
java -cp bin cli.CommandLineInterface \
  data/mushroom_database.txt \
  data/mushroom_profits.txt \
  1000 \
  0.7
```

**Debug mode with best-first search:**
```bash
java -cp bin cli.CommandLineInterface \
  data/retail_database.txt \
  data/retail_profits.txt \
  500 \
  0.5 \
  --debug \
  --strategy BEST_FIRST
```

**Save results to file with optimized collector:**
```bash
java -cp bin cli.CommandLineInterface \
  data/kosarak_database.txt \
  data/kosarak_profits.txt \
  200 \
  0.3 \
  --output results.txt \
  --collector LAZY
```

---

## Input Data Format

### Transaction Database File

Plain text file, one transaction per line:

```
itemId:quantity:probability itemId:quantity:probability ...
```

**Example** (`chess_database.txt`):
```
15:1:1.0 3:1:1.0 4:1:1.0 9:1:1.0
27:1:1.0 1:1:1.0 3:1:0.85
14:2:0.9 2:1:1.0 4:1:0.75
```

**Rules:**
- Items separated by spaces
- Each item: `itemId:quantity:probability`
- `itemId`: Integer (any positive number)
- `quantity`: Integer (how many units)
- `probability`: Decimal 0-1 (certainty of presence, 1.0 = certain)
- Empty lines ignored
- Malformed lines skipped with warning

### Profit Table File

Plain text file, one item per line:

```
itemId profit
```

**Example** (`chess_profits.txt`):
```
1 6.74
2 -3.63
3 9.26
4 9.58
5 -3.94
```

**Rules:**
- Space or colon separated: `itemId profit` or `itemId:profit`
- Negative profits allowed (represents costs/losses)
- Items not in this file have profit = 0
- Malformed lines skipped

---

## Understanding Results

### Output Format

```
=================================================
TOP-100 HIGH-UTILITY PATTERNS
=================================================
Rank    Pattern                  Expected Util   Exist Prob
--------------------------------------------------------------
1       {3, 4, 8}               1234.5678       0.850000
2       {4, 8, 12}              1150.2345       0.780000
3       {2, 5, 7, 11}           1089.3421       0.650000
...
=================================================
Execution time: 12.345 seconds
Patterns found: 100
Memory used: 256.78 MB
=================================================
```

### Column Explanations

- **Rank**: Position in top-K (1 = highest value)
- **Pattern**: Set of item IDs in the combination
- **Expected Util**: Expected profit considering probabilities (higher = more valuable)
- **Exist Prob**: Reliability score 0-1 (probability pattern actually occurs)

### Interpreting Results

**Pattern {3, 4, 8} with EU=1234.57, EP=0.85:**
- Items 3, 4, and 8 together generate expected profit of $1234.57
- This combination has 85% chance of occurring in at least one transaction
- Ranked #1 means this is the most valuable pattern found

**Negative Expected Utility:**
- Some patterns may have negative EU (net cost instead of profit)
- Still included if they rank in top-K (might be top "losses to avoid")

---

## Performance Tuning

### For Faster Execution

1. **Use Best-First Search** (reduces search space):
   ```bash
   --strategy BEST_FIRST
   ```

2. **Use Lazy Collector** (best for high throughput):
   ```bash
   --collector LAZY
   ```

3. **Enable Parallelization** (default, uses all CPU cores):
   ```bash
   # Already enabled by default
   # To disable: --no-parallel
   ```

4. **Increase Memory** (for large datasets):
   ```bash
   java -Xmx8g -cp bin cli.CommandLineInterface ...
   ```

### Strategy Comparison

| Strategy | Best For | Memory | Speed |
|----------|----------|--------|-------|
| **DFS** (default) | General purpose | Low | Medium |
| **BEST_FIRST** | Dense datasets, need fast pruning | Medium | Fast |
| **BREADTH_FIRST** | Small patterns, level-by-level | High | Medium |
| **IDDFS** | Memory-constrained systems | Low | Slow |

### Collector Comparison

| Collector | Best For | Speedup | Thread Safety |
|-----------|----------|---------|---------------|
| **BASELINE** (default) | Small K, simple use | 1.0× | Full |
| **SHARDED** | 16-32 core systems | 4.8× | Full |
| **LAZY** | High throughput, many patterns | 6.7× | Full |

**Recommendation**: Use `--strategy BEST_FIRST --collector LAZY` for best performance on multi-core systems.

---

## Troubleshooting

### Out of Memory Error

**Symptom:**
```
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
```bash
# Increase heap size to 8GB
java -Xmx8g -cp bin cli.CommandLineInterface ...
```

### No Patterns Found

**Symptom:**
```
Patterns found: 0
```

**Causes:**
1. `min_probability` too high (try lower value like 0.01)
2. All profits negative (no positive-utility patterns)
3. Database too small

**Solution:**
```bash
# Try lower probability threshold
java -cp bin cli.CommandLineInterface data.txt profits.txt 100 0.01 --debug
```

### Slow Execution

**Symptom:** Takes hours to complete

**Solutions:**
1. Reduce K (fewer patterns = faster)
2. Use `--strategy BEST_FIRST`
3. Use `--collector LAZY`
4. Increase `min_probability` (filters more early)
5. Check if parallelization enabled (should be by default)

### File Not Found

**Symptom:**
```
Error: Could not read file: data/database.txt
```

**Solution:**
- Check file path is correct (use absolute paths if needed)
- Ensure file exists and is readable
- Check file encoding is UTF-8 or ASCII

### Malformed Input Warnings

**Symptom:**
```
Warning: Skipping malformed line 42: invalid format
```

**Solution:**
- Check line 42 in input file
- Ensure format is correct: `itemId:quantity:probability`
- Remove extra spaces, special characters
- Ensure probabilities are between 0 and 1

---

# For Programmers

## Architecture Overview

PTK-HUIM is a **3-phase mining system** with a clean separation of concerns across four architectural layers:

```
┌─────────────────────────────────────────────────────────┐
│                    CLI Layer                             │
│  (ArgumentParser, CommandLineInterface, ResultFormatter)│
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│                 Application Layer                        │
│  (MiningOrchestrator, MiningConfiguration, Factories)   │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│                  Domain Layer                            │
│  (SearchEngine, PatternCollector, Models, Algorithms)   │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│              Infrastructure Layer                        │
│  (I/O Readers, Parallel Tasks, Computation Utilities)   │
└─────────────────────────────────────────────────────────┘
```

### Mining Workflow

**Phase 1: Preprocessing**
1. Compute PTWU and EP for all single items (parallel)
2. Filter unreliable items (EP < minProb)
3. Rank items by PTWU in ascending order
4. Build UPU-Lists for all single items (parallel)

**Phase 2: Initialization**
1. Evaluate all 1-itemsets as patterns
2. Collect top-K from single items
3. Snapshot current threshold for computing global prefix cutoff

**Phase 3: Mining**
1. Prefix-growth mining with chosen search strategy
2. Multi-tier pruning (EP, PTWU, PUB)
3. Dynamic threshold continuously updated as better patterns found
4. Parallel execution with ForkJoin work-stealing (optional)

### Key Design Patterns

- **Strategy Pattern**: Pluggable search engines (DFS, BFS, Best-First, IDDFS)
- **Factory Pattern**: SearchEngineFactory, TopKCollectorFactory
- **Template Method**: SearchEngine abstract class with customizable hooks
- **Builder Pattern**: UPUListBuilder for constructing data structures
- **Dependency Injection**: Configuration objects passed through layers

---

## Project Structure

```
src/
├── cli/                              # Command-line interface
│   ├── ArgumentParser.java           # CLI argument parsing
│   ├── CommandLineInterface.java     # Main entry point
│   ├── DataLoader.java               # File loading orchestration
│   ├── ResultFormatter.java          # Output formatting
│   └── ComparisonBenchmark.java      # Benchmarking tool
│
├── application/                      # Application orchestration
│   ├── MiningOrchestrator.java       # Main 3-phase coordinator
│   ├── MiningConfiguration.java      # Configuration object
│   ├── MiningContext.java            # Shared mining state
│   ├── SearchEngineFactory.java      # Engine creation
│   └── OrchestratorConfiguration.java # Orchestrator config
│
├── domain/                           # Core business logic
│   ├── engine/                       # Search algorithms
│   │   ├── SearchEngine.java         # Abstract base class
│   │   ├── PatternGrowthEngine.java  # DFS implementation
│   │   ├── BestFirstSearchEngine.java # Best-first implementation
│   │   ├── BreadthFirstSearchEngine.java # BFS implementation
│   │   ├── IterativeDeepeningEngine.java # IDDFS implementation
│   │   ├── SearchNode.java           # Node representation
│   │   ├── UPUListJoiner.java        # Two-pointer join
│   │   ├── UPUListJoiner_ExponentialSearch.java
│   │   ├── UPUListJoiner_BinarySearch.java
│   │   └── PatternCollector.java     # Pattern collection logic
│   │
│   ├── collection/                   # Top-K collectors
│   │   ├── TopKCollectorInterface.java
│   │   ├── TopKPatternCollector.java # Baseline collector
│   │   ├── ShardedTopKCollector.java # Sharded collector
│   │   ├── LazyTopKCollector.java    # Lazy collector
│   │   └── TopKCollectorFactory.java # Factory
│   │
│   └── model/                        # Data models
│       ├── Transaction.java          # Transaction representation
│       ├── ItemInfo.java             # Item metadata
│       ├── ProfitTable.java          # Profit mapping
│       ├── HighUtilityPattern.java   # Pattern result
│       ├── UtilityProbabilityList.java # UPU-List structure
│       └── ItemRanking.java          # Item ordering
│
└── infrastructure/                   # Technical infrastructure
    ├── io/                           # File I/O
    │   ├── TransactionReader.java    # Interface
    │   ├── FileTransactionReader.java # Implementation
    │   ├── ProfitTableReader.java    # Interface
    │   └── FileProfitTableReader.java # Implementation
    │
    ├── parallel/                     # Parallelization
    │   ├── PrefixMiningTask.java     # ForkJoin recursive task
    │   ├── WorkBalancedSplitter.java # Work splitting
    │   └── TwoThresholdCoordinator.java # Thread coordination
    │
    ├── computation/                  # Computational utilities
    │   ├── PTWUCalculator.java       # PTWU computation
    │   ├── SuffixSumCalculator.java  # Suffix sum arrays
    │   ├── ProbabilityModel.java     # Log-space probability
    │   └── UPUListBuilder.java       # UPU-List construction
    │
    └── util/                         # Utilities
        ├── ValidationUtils.java      # Input validation
        └── NumericalConstants.java   # Constants (epsilon, etc.)
```

---

## Core Components

### 1. UPU-List (Utility-Probability-Utility List)

**What**: The fundamental data structure representing itemset occurrences across transactions.

**Structure** (`UtilityProbabilityList.java`):
```java
class UtilityProbabilityList {
    int[] transactionIds;        // TIDs where itemset appears
    double[] utilities;          // Utility per transaction
    double[] logProbabilities;   // Log(probability) for stability
    double[] remainingUtilities; // Suffix utilities for PUB

    double expectedUtility;      // Pre-computed EU
    double existentialProb;      // Pre-computed EP
    double ptwu;                 // Pre-computed PTWU
    double pub;                  // Pre-computed PUB
}
```

**Purpose**:
- Compact transactional projection
- O(1) threshold checks via pre-computed statistics
- O(n) joins via two-pointer/binary search

### 2. Search Engines

**Interface**: All extend `SearchEngine.java`

**Implementations**:
- `PatternGrowthEngine.java`: DFS with recursion (low memory)
- `BestFirstSearchEngine.java`: Priority queue, max PUB first (fast pruning)
- `BreadthFirstSearchEngine.java`: FIFO queue, level-order (good for small patterns)
- `IterativeDeepeningEngine.java`: Repeated DFS with depth limits (BFS memory, DFS order)

**Key Method**:
```java
interface SearchEngine {
    // Explores all extensions of the given prefix
    // Performs multi-tier pruning (EP, PTWU, PUB)
    // Collects qualifying patterns via collector.tryCollect()
    // Thread-safe: called by multiple threads in parallel
    void exploreExtensions(UtilityProbabilityList prefix, int startIndex);
}
```

### 3. Top-K Collectors

**Interface**: `TopKCollectorInterface.java`

**Implementations**:

**Baseline** (`TopKPatternCollector.java`):
- TreeSet (min-heap) + HashMap for duplicate detection
- Thread-safe with ReentrantLock for mutations
- Volatile threshold for lock-free fast-path reads
- 1.0× baseline performance

**Sharded** (`ShardedTopKCollector.java`):
- 16 parallel shards with separate locks
- Hash-based partitioning
- 4.8× speedup on 16-32 cores

**Lazy** (`LazyTopKCollector.java`):
- Batched updates with amortized insertion
- Periodic consolidation
- 6.7× speedup, best for high throughput

**Key Methods**:
```java
interface TopKCollectorInterface {
    // Thread-safe pattern admission (may reject if below threshold)
    boolean tryCollect(UtilityProbabilityList candidate);

    // Get current admission threshold (k-th largest EU, 0.0 if not full)
    double getAdmissionThreshold();

    // Final sorted results (highest EU first)
    List<HighUtilityPattern> getCollectedPatterns();

    // Capacity and current size
    int getCapacity();
    int getCurrentSize();
}
```

### 4. UPU-List Join Strategies

**Purpose**: Extend UPU-List X with item Y to create UPU-List for {X ∪ Y}

**Algorithms** (all O(n) worst-case):

**Two-Pointer** (`UPUListJoiner.java`):
- Simultaneous scan of both sorted TID arrays
- Best for general use (15-25% faster than alternatives)

**Exponential Search** (`UPUListJoiner_ExponentialSearch.java`):
- Exponential probe + binary search
- Good when one list much smaller

**Binary Search** (`UPUListJoiner_BinarySearch.java`):
- Linear scan + binary search
- Good for highly skewed sizes

### 5. Parallel Execution

**Task**: `PrefixMiningTask.java` (extends `RecursiveAction`)

**Mechanism**:
- ForkJoin work-stealing framework
- Work-balanced splitting by PTWU weights
- Fine-grain threshold: split prefixes with >16 items

**Thread Safety**:
- `TopKCollector`: Thread-safe with lock-guarded mutations
- `admissionThreshold`: Volatile field for lock-free reads by mining threads
- Threshold continuously updated under lock as patterns collected
- Global cutoff computed from Phase 2 snapshot (immutable during mining)

---

## Extending the System

### Add a New Search Strategy

1. **Create class implementing `SearchEngine`**:
```java
public class MyCustomEngine implements SearchEngine {
    private final double minProbability;
    private final UPUListJoinerInterface joiner;
    private final TopKCollectorInterface collector;
    private final List<Integer> sortedItems;
    private final Map<Integer, UtilityProbabilityList> singleItemLists;

    public MyCustomEngine(double minProbability,
                          UPUListJoinerInterface joiner,
                          TopKCollectorInterface collector,
                          ItemRanking itemRanking,
                          Map<Integer, UtilityProbabilityList> singleItemLists,
                          double initialThreshold) {
        this.minProbability = minProbability;
        this.joiner = joiner;
        this.collector = collector;
        this.sortedItems = itemRanking.getSortedItems();
        this.singleItemLists = singleItemLists;
        // Note: initialThreshold passed but not used in actual search
    }

    @Override
    public void exploreExtensions(UtilityProbabilityList prefix, int startIndex) {
        // Read current threshold from collector
        double currentThreshold = collector.getAdmissionThreshold();

        // Your custom traversal logic
        for (int i = startIndex; i < sortedItems.size(); i++) {
            int extItem = sortedItems.get(i);
            UtilityProbabilityList extList = singleItemLists.get(extItem);
            if (extList == null) continue;

            // Join prefix with extension item
            UtilityProbabilityList joined = joiner.join(prefix, extList, extItem, currentThreshold);
            if (joined == null) continue;

            // Multi-tier pruning
            if (joined.existentialProbability < minProbability) continue;
            if (joined.ptwu < currentThreshold) continue;
            if (joined.positiveUpperBound < currentThreshold) continue;

            // Try to collect if EU >= threshold
            if (joined.expectedUtility >= currentThreshold) {
                collector.tryCollect(joined);
                currentThreshold = collector.getAdmissionThreshold(); // Refresh
            }

            // Recursively explore (your custom order/logic here)
            exploreExtensions(joined, i + 1);
        }
    }
}
```

2. **Register in `SearchEngineFactory`**:
```java
public static SearchEngine createEngine(String strategy, ...) {
    switch (strategy.toUpperCase()) {
        case "MY_CUSTOM":
            return new MyCustomEngine(config, collector);
        // ... existing cases ...
    }
}
```

3. **Add CLI option** in `ArgumentParser.java`

### Add a New Join Strategy

1. **Implement `UPUListJoinerInterface`**:
```java
public class UPUListJoiner_MyAlgorithm implements UPUListJoinerInterface {
    @Override
    public UtilityProbabilityList join(UtilityProbabilityList list1,
                                        UtilityProbabilityList list2,
                                        int extensionItem,
                                        double currentThreshold) {
        // Compute joined PTWU
        double joinedPTWU = Math.min(list1.ptwu, list2.ptwu);

        // Early pruning if below threshold
        if (joinedPTWU < currentThreshold) {
            return null;
        }

        // Your intersection logic: merge TID arrays
        // Return new UPU-List for merged itemset
    }
}
```

2. **Register in `SearchEngineFactory`** when creating joiners

### Add a New Collector

1. **Implement `TopKCollectorInterface`**:
```java
public class MyTopKCollector implements TopKCollectorInterface {
    private final int capacity;
    public volatile double admissionThreshold = 0.0;

    public MyTopKCollector(int k) {
        this.capacity = k;
    }

    @Override
    public boolean tryCollect(UtilityProbabilityList candidate) {
        // Fast-path rejection
        if (candidate.expectedUtility < admissionThreshold) {
            return false;
        }

        // Your thread-safe collection logic
        // Update admissionThreshold when patterns admitted/evicted
        return true;
    }

    @Override
    public double getAdmissionThreshold() {
        return admissionThreshold; // Volatile read
    }

    @Override
    public List<HighUtilityPattern> getCollectedPatterns() {
        // Return sorted top-K (highest EU first)
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public int getCurrentSize() {
        // Return current pattern count
    }
}
```

2. **Register in `TopKCollectorFactory`**

### Modify Pruning Strategies

**Location**: Inside your `SearchEngine` implementation's `exploreExtensions()` method

```java
@Override
public void exploreExtensions(UtilityProbabilityList prefix, int startIndex) {
    double currentThreshold = collector.getAdmissionThreshold();

    for (int i = startIndex; i < sortedItems.size(); i++) {
        // ... join logic ...

        // Standard three-tier pruning
        if (joined.existentialProbability < minProbability) continue;
        if (joined.ptwu < currentThreshold) continue;
        if (joined.positiveUpperBound < currentThreshold) continue;

        // Add custom pruning condition
        if (joined.itemset.size() > MAX_PATTERN_SIZE) continue;

        // ... collection and recursion ...
    }
}
```

---

## Build and Test

### Compilation

```bash
# Compile all sources
find src -name "*.java" | xargs javac -d bin -cp "src"

# Compile with debugging symbols
find src -name "*.java" | xargs javac -g -d bin -cp "src"
```

### Run Tests

**Correctness Test**:
```bash
java -cp bin test.TopKCollectorCorrectnessTest
```

**Benchmarks**:
```bash
# Collector benchmark
java -cp bin benchmark.TopKCollectorBenchmark

# Strategy comparison
java -cp bin cli.ComparisonBenchmark data/chess_database.txt data/chess_profits.txt
```

### Sample Datasets

Located in `data/`:
- `chess_database.txt` / `chess_profits.txt` (dense, 3,196 transactions)
- `mushroom_database.txt` / `mushroom_profits.txt` (dense, 8,124 transactions)
- `connect_database.txt` / `connect_profits.txt` (dense, 67,557 transactions)
- `retail_database.txt` / `retail_profits.txt` (sparse, 88,162 transactions)
- `kosarak_database.txt` / `kosarak_profits.txt` (sparse, 990,002 transactions)

### Memory Profiling

```bash
# Enable GC logging
java -Xmx8g -verbose:gc -XX:+PrintGCDetails -cp bin cli.CommandLineInterface ...

# Use profiler
java -agentlib:hprof=heap=sites -cp bin cli.CommandLineInterface ...
```

---

## Configuration Options

### Mining Configuration

**Class**: `MiningConfiguration.java`

**Key Parameters**:
```java
class MiningConfiguration {
    int k;                          // Number of top patterns
    double minProbability;          // EP threshold
    boolean parallelPhase1;         // Parallel preprocessing
    boolean parallelPhase1d;        // Parallel UPU-List building
    boolean parallelPhase3;         // Parallel mining
    String searchStrategy;          // DFS, BEST_FIRST, etc.
    String joinStrategy;            // TWO_POINTER, etc.
    String collectorType;           // BASELINE, SHARDED, LAZY
}
```

**Defaults**:
- All parallelization enabled
- Search: DFS
- Join: TWO_POINTER
- Collector: BASELINE

### Orchestrator Configuration

**Class**: `OrchestratorConfiguration.java`

**Toggles**:
- Phase 1a parallelization
- Phase 1d parallelization
- Phase 3 parallelization
- Prefix fine-grain splitting threshold (default: 16)

### Numerical Constants

**Class**: `NumericalConstants.java`

```java
public static final double EPSILON = 1e-9;  // Floating-point comparison
public static final double LOG_ZERO = -1e100;  // Log(0) approximation
```

---

## API Examples

### Programmatic Usage (Without CLI)

```java
// 1. Load data
TransactionReader txReader = new FileTransactionReader("data.txt");
ProfitTableReader profitReader = new FileProfitTableReader("profits.txt");

List<Transaction> transactions = txReader.readTransactions();
ProfitTable profitTable = profitReader.readProfitTable();

// 2. Configure mining
MiningConfiguration config = new MiningConfiguration();
config.setK(100);
config.setMinProbability(0.1);
config.setSearchStrategy("BEST_FIRST");
config.setCollectorType("LAZY");

// 3. Run mining
MiningOrchestrator orchestrator = new MiningOrchestrator(
    transactions, profitTable, config
);
List<HighUtilityPattern> results = orchestrator.executeFullPipeline();

// 4. Process results
for (HighUtilityPattern pattern : results) {
    System.out.println("Pattern: " + pattern.getItemset());
    System.out.println("EU: " + pattern.getExpectedUtility());
    System.out.println("EP: " + pattern.getExistentialProbability());
}
```

### Custom Collector Integration

```java
// Create custom collector
TopKCollectorInterface collector = new MyCustomCollector(config.getK());

// Create engine with custom collector
SearchEngine engine = SearchEngineFactory.createEngine(
    config.getSearchStrategy(),
    config,
    collector
);

// Mine patterns
engine.minePatterns();

// Get results
List<HighUtilityPattern> results = collector.getTopKPatterns();
```

### Benchmarking Strategies

```java
// Compare all strategies
String[] strategies = {"DFS", "BEST_FIRST", "BREADTH_FIRST", "IDDFS"};

for (String strategy : strategies) {
    config.setSearchStrategy(strategy);

    long start = System.currentTimeMillis();
    List<HighUtilityPattern> results = orchestrator.executeFullPipeline();
    long elapsed = System.currentTimeMillis() - start;

    System.out.println(strategy + ": " + elapsed + " ms");
}
```

---

## Design Decisions

### Why UPU-Lists?

**Alternative**: Store full transactions and recompute statistics on-demand

**Chosen**: Pre-computed UPU-Lists with cached statistics

**Rationale**:
- O(1) threshold checks vs O(|D|) repeated scans
- Memory trade-off acceptable for modern systems
- Locality benefits from sorted TID arrays

### How Does Threshold Management Work?

**Single Dynamic Threshold**:
- The algorithm uses ONE threshold: `collector.getAdmissionThreshold()`
- This threshold continuously rises as better patterns are discovered
- Mining threads read it lock-free (volatile) for fast pruning checks
- Updated under lock when patterns are admitted/evicted

**Global Cutoff Optimization**:
- Phase 2 captures a threshold snapshot for computing which prefixes to skip
- Items with PTWU below this cutoff cannot produce top-K patterns
- Entire low-PTWU prefixes are skipped before mining starts
- This is a performance optimization, not a correctness requirement

**Thread Safety**:
- Volatile reads allow slightly stale threshold values (safe, just less aggressive pruning)
- Collector's `tryCollect()` uses lock for correctness (no false admissions)

### Why Multiple Search Strategies?

**Research Goal**: Comparative evaluation of traversal orders

**Benefit**:
- Researchers can benchmark algorithmic variants
- Different strategies optimal for different data characteristics
- Educational value for understanding trade-offs

### Why Log-Space Probabilities?

**Problem**: Multiplying many small probabilities causes underflow

**Example**: P = 0.9^1000 ≈ 1.75e-46 (underflows to 0.0 in double)

**Solution**: log(P) = 1000 × log(0.9) = -105.36 (stable)

**Benefit**: Numerical stability for long patterns

---

## Performance Characteristics

### Time Complexity

- **Phase 1a**: O(|D| × |T̄|) parallel
- **Phase 1d**: O(|I| × |D|) parallel
- **Phase 3**: O(search space) with pruning, parallel

Where:
- |D| = number of transactions
- |I| = number of items
- |T̄| = average transaction length

### Space Complexity

- **UPU-Lists**: O(|I| × |D|) worst-case
- **Search frontier**: O(depth) for DFS, O(frontier) for BFS/Best-First
- **Top-K Collector**: O(k)

### Scalability

**Tested on**:
- Up to 990,002 transactions (Kosarak dataset)
- Up to 41,270 unique items
- Multi-core systems (1-32 cores)

**Parallelization Speedup**:
- Phase 1a: 2.5-3× on 8 cores
- Phase 1d: 2-2.5× on 8 cores
- Phase 3: 2-3× on 8 cores (depends on work distribution)

---

## Code Quality

### Documentation

- **JavaDoc**: All public APIs documented
- **Inline comments**: Complex algorithms explained
- **README**: This document (practical guide)
- **Theory docs**: Separate academic documentation in `documents/`

### Code Style

- **Naming**: CamelCase classes, camelCase methods
- **Package structure**: Clear layer separation
- **Immutability**: Prefer immutable where possible
- **Thread safety**: Explicitly documented

### Testing

- **Unit tests**: Collector correctness test
- **Integration tests**: Full pipeline validation
- **Benchmarks**: Performance comparison framework

---

## Common Programming Tasks

### Add Debug Logging

```java
// In your SearchEngine implementation
@Override
public void exploreExtensions(UtilityProbabilityList prefix, int startIndex) {
    if (DEBUG_MODE) {
        System.out.println("[DEBUG] Exploring prefix: " + prefix.itemset +
                           " EU=" + prefix.expectedUtility +
                           " PTWU=" + prefix.ptwu);
    }

    // ... rest of exploration logic ...
}
```

### Custom Pruning Condition

```java
// In your SearchEngine implementation
@Override
public void exploreExtensions(UtilityProbabilityList prefix, int startIndex) {
    double currentThreshold = collector.getAdmissionThreshold();

    for (int i = startIndex; i < sortedItems.size(); i++) {
        // ... join logic ...

        // Standard pruning
        if (joined.existentialProbability < minProbability) continue;
        if (joined.ptwu < currentThreshold) continue;
        if (joined.positiveUpperBound < currentThreshold) continue;

        // Add your custom condition
        if (joined.itemset.size() > MAX_PATTERN_SIZE) {
            continue; // Skip patterns larger than max size
        }

        // ... collection and recursion ...
    }
}
```

### Extract Intermediate Results

```java
// In your SearchEngine implementation
@Override
public void exploreExtensions(UtilityProbabilityList prefix, int startIndex) {
    double currentThreshold = collector.getAdmissionThreshold();

    for (int i = startIndex; i < sortedItems.size(); i++) {
        // ... join and pruning ...

        // Log before attempting collection
        System.out.printf("Candidate: %s, EU=%.2f, EP=%.4f%n",
                          joined.itemset,
                          joined.expectedUtility,
                          joined.existentialProbability);

        if (joined.expectedUtility >= currentThreshold) {
            boolean collected = collector.tryCollect(joined);
            if (collected) {
                System.out.println("  -> COLLECTED!");
            }
        }

        // ... recursion ...
    }
}
```

### Profile Specific Phase

```java
// In MiningOrchestrator
long phase1Start = System.currentTimeMillis();
ItemRanking ranking = executePhase1(transactions, profitTable, config);
long phase1Time = System.currentTimeMillis() - phase1Start;
System.out.println("Phase 1: " + phase1Time + " ms");
```

---

**End of README**
