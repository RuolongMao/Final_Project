# Large Scale Data Processing: Final Project

## Team member: Hoiting Mok, Ruolong Mao

## 1. A table containing the objective of the solution (i.e. the size of matching or the number of disagreements of clustering) you obtained for each test case. The objectives must correspond to the matchings or the clusterings in your output files.

| Dataset | Edges | No. Disagreements | No. Clusters |
|---------|-------|------------------|--------------|
| log_normal_100 | 2,671 | 1,711 | 11 |
| musae_ENGB | 35,324 | 33,162 | 5,386 |
| soc-pokec-relationships | 22,301,964 | 20,767,607 | 209,273 |
| soc-LiveJournal1 | 42,851,237 | 36,758,906 | 464,347 |
| com-orkut.ungraph | 63,555,749 | 110,073,940 | 47,165 |
| twitter_original_edges | 117,185,083 | 85,131,188 | 1,116,196 |

## 2. An estimate of the amount of computation used for each test case. For example, "the program runs for 15 minutes on a 2x4 N1 core CPU in GCP." If you happen to be executing multiple algorithms on a test case, report the total running time.

| Dataset | Duration | Machine |
|---------|----------|---------|
| log_normal_100 | 3.036s | Local |
| musae_ENGB | 5.736s | Local |
| soc-pokec-relationships | 4min 8s | GCP |
| soc-LiveJournal1 | 12min 49s | GCP |
| com-orkut.ungraph | 8min 6s | GCP |
| twitter_original_edges | 19min 26s | GCP |

### GCP Cluster Configuration
- Master Node: Standard (1 master, N workers)
- Machine Type: n1-standard-4
- Worker Node: 4
- Machine Type: n1-standard-4 (4x4 N1 core CPU)
- Properties:
  - spark.executor.memory=8g
  - spark.driver.memory=4g

## 3. Approach Description

Our implementations define a distributed pivot-based clustering algorithm using Apache Spark's GraphX library. The main objective is to partition a graph into clusters by iteratively identifying pivot nodes based on randomly assigned priority values and grouping them with their neighbors.

Three different approaches are used to perform clustering depending on the graph size and computational constraints. For all three approaches, to ensure randomness, each run's random function is seeded with the system's current time mill with the exception of the first graph (as one seed was identified to be outperforming others).

### Clustering with Neighbor Connectivity Check and Optimization (for graph 1)
For the smallest log_normal_100 graph, we used a two-step algorithm:

#### Initial clustering using the Parallelized PIVOT algorithm
- We first assign each vertex a random pi value. Then, we identify the vertex with the smallest pi value in comparison with all of its neighbors as our pivot candidates.
- Using the pivot candidates, each pivot's neighbors are examined to determine if they are also interconnected in consideration of how the number of disagreements is calculated based on both clusters crossing edges and non-existed edges inside clusters.
- A pivot's neighbor would be clustered into a cluster only if it is considered to be well connected with other neighbors (at least half) of said pivot. This is to ensure that neighbors of a selected pivot are not blindly clustered when they might not be connected to other members of the cluster. If a vertex is considered to be not well connected to others, it is filtered out from the next round of consideration. We also continue filtering until no more vertices are removed, guaranteeing all remaining vertices meet the connectivity threshold.
- Also note that the threshold changes with the number of iterations. Threshold is loosened to start out strictly and then allow recovery when clusters become smaller, which helps with preventing over-fragmentation of clusters – something we noticed with earlier experimentations.

#### Refinement and optimization
- After the initial clustering, we calculate current disagreements within the algorithm and check whether moving each vertex to a neighboring cluster can decrease the overall number of edges crossing clusters. If so, the vertex is reassigned. The loop continues until no further improvements can be done or a pre-set number of iterations is reached.
- A range of numbers from 5 to 50 iterations are used to determine the best outcome from the verifier. The number of iterations we used to achieve the result in the table is 10.

### Clustering with Neighbor Connectivity Check (for graph 2)
For the second smallest musae_ENGB graph, we discovered that the refinement process previously working well on the 100 vertices graph is adversely interfering with our verifier outcome. Therefore, we reversed back a step and used the pivot selection and neighbor filtering method described in the previous subsection.

### Parallel PIVOT (for large graphs: graph 3 and onwards)
For the larger graphs, we simplify the process into its barebone for scalability. However, the core ideas remain unchanged:
- Each vertex receives a random double value (piValue) used to determine local pivot points.
- For each vertex, its direct neighbors are collected using edge traversal.
- Note that priority maps and vertex sets are broadcast to minimize shuffling and improve performance in a distributed setting. This is to maximize the benefits of the implementation with an efficient use of RDDs.
- A vertex is considered a pivot if its priority is less than or equal to all its neighbors'.
- Each pivot and its neighbors form a new cluster. Vertices are assigned a cluster ID.
- Clustered vertices are removed from the current graph; the loop continues on remaining vertices.

### General Strategy
Our clustering framework is designed to be adaptive and resource-aware, allowing us to select from multiple algorithmic approaches depending on the size and complexity of the input graph as well as the available computational resources. When a new test case is introduced, we assess the characteristics of the graph—primarily its scale (number of vertices and edges) and the memory and compute availability in the environment.

If the graph is small to moderately sized, and system resources are sufficient to support more computation-heavy techniques, we apply either Approach 1 or Approach 2. These methods aim to produce high-quality clusters with minimal internal noise and lower disagreement metrics. These approaches are particularly effective for graphs with moderate complexity, where computation cost is manageable and fine-grained clustering is desired.

If the input graph is very large, or if the system is running under limited memory conditions (e.g., heap space constraints or risk of stack overflow), we employ Approach 3, a simplified version of the pivot clustering algorithm. While this method may yield clusters of slightly lower quality, it ensures that the clustering process completes efficiently and reliably, even for datasets with millions of nodes and edges. It is a pragmatic fallback that prioritizes scalability and fault tolerance in memory-constrained environments without crashing the heap space memory or overflowing the stack.

## 4. Algorithm Advantages and Guarantees

The parallelized PIVOT algorithm guarantees a small number of shuffle rounds in a distributed system. It runs in O(log log n) rounds, where n is the number of vertices. Moreover, it guarantees a 3-approximation to the optimal correlation clustering.

The PIVOT + optimization algorithm, although relatively computationally expensive on large graphs, gives very high-quality clustering results (better approximation of the optimal clustering) since the local optimization phase iteratively reduces the disagreement by greedily reassigning nodes to better clusters.

### Reference & Sketch
Ailon, N., Charikar, M., & Newman, A. (2008). Aggregating Inconsistent Information: Ranking and Clustering. Journal of the ACM (JACM), 55(5).  

Chierichetti, Flavio, Nilesh Dalvi, and Ravi Kumar. "Correlation clustering in mapreduce." Proceedings of the 20th ACM SIGKDD international conference on Knowledge discovery and data mining. 2014.
