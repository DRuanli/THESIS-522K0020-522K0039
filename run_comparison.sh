#!/bin/bash

##############################################################################
# PTK-HUIM Comparison Benchmark Runner
#
# Compares specific dimensions:
# 1. PARALLELISM - DFS + TwoPointer + BASELINE collector + variant parallel modes
# 2. JOIN - DFS + Full Parallel + BASELINE collector + variant join strategies
# 3. TRAVERSAL - TwoPointer + Full Parallel + BASELINE collector + variant search strategies
# 4. TOPK - DFS + TwoPointer + Full Parallel + variant TopK collectors
#
# Output: Results saved in .txt format with organized folder structure:
#         benchmark_results/<dataset>/<comparison_type>/k<value>/
##############################################################################

set -e

# Check if compiled
if [ ! -d "bin" ] || [ ! -f "bin/cli/ComparisonBenchmark.class" ]; then
    echo "Compiling Java source files..."
    find src -name "*.java" -type f -exec javac -d bin -cp "src" {} +
    echo "Compilation complete"
    echo ""
fi

# Default parameters (modify these)
DATABASE="data/chess_database.txt"
PROFITS="data/chess_profits.txt"
K="1000"  # Comma-separated list for multiple k values, or single value like "1000"
MINPROB=0.7
DATASET="CHESS"
OUTPUT_DIR="results"

echo "=================================================================="
echo "PTK-HUIM Comparison Benchmark"
echo "=================================================================="
echo "Dataset: $DATASET"
echo "K values: $K"
echo "Min Prob: $MINPROB"
echo "=================================================================="
echo ""

# Uncomment the comparison you want to run:

# Comparison 1: Parallelism modes (DFS + TwoPointer fixed)
#echo "Running PARALLELISM comparison..."
#java -Xmx4g -cp bin cli.ComparisonBenchmark \
#    "$DATABASE" "$PROFITS" $K $MINPROB "$DATASET" PARALLELISM "$OUTPUT_DIR"

# Comparison 2: Join strategies (DFS + Full Parallel fixed)
#echo "Running JOIN comparison..."
#java -Xmx4g -cp bin cli.ComparisonBenchmark \
#     "$DATABASE" "$PROFITS" $K $MINPROB "$DATASET" JOIN "$OUTPUT_DIR"

# Comparison 3: Traversal strategies (TwoPointer + Full Parallel fixed)
#echo "Running TRAVERSAL comparison..."
#java -Xmx4g -cp bin cli.ComparisonBenchmark \
#     "$DATABASE" "$PROFITS" $K $MINPROB "$DATASET" TRAVERSAL "$OUTPUT_DIR"

# Comparison 4: TopK collectors (DFS + TwoPointer + Full Parallel fixed)
#echo "Running TOPK comparison..."
#java -Xmx4g -cp bin cli.ComparisonBenchmark \
#     "$DATABASE" "$PROFITS" $K $MINPROB "$DATASET" TOPK "$OUTPUT_DIR"

# Run all comparisons
echo "Running ALL comparisons..."
java -Xmx4g -cp bin cli.ComparisonBenchmark \
     "$DATABASE" "$PROFITS" $K $MINPROB "$DATASET" ALL "$OUTPUT_DIR"

echo ""
echo "=================================================================="
echo "Benchmark Complete!"
echo "Results saved in: $OUTPUT_DIR/<dataset>/<comparison_type>/k<value>/"
echo "Format: .txt files with detailed statistics and summary tables"
echo "=================================================================="
