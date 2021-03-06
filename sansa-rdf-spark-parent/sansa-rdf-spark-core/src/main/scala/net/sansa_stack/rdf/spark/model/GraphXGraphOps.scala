package net.sansa_stack.rdf.spark.model

import org.apache.spark.SparkContext
import org.apache.spark.graphx.PartitionStrategy.RandomVertexCut
import org.apache.spark.rdd.RDD
import org.apache.spark.graphx.{Graph => SparkGraph, EdgeTriplet, VertexId, Edge}

import scala.reflect.ClassTag
import scala.util.hashing.MurmurHash3

/**
 * Spark/GraphX based implementation of RDFGraphOps
 *
 * @author Nilesh Chakraborty <nilesh@nileshc.com>
 */
trait GraphXGraphOps[Rdf <: SparkGraphX{ type Blah = Rdf }]
  extends RDFGraphOps[Rdf] with Serializable { this: RDFNodeOps[Rdf] =>

  protected val sparkContext: SparkContext

  // graph
  def loadGraphFromNTriples(file: String, baseIRI: String): Rdf#Graph =
    makeGraph(sparkContext.textFile(file).mapPartitions {
      case it =>
        fromNTriples(it.mkString("\n"), baseIRI).iterator
    })

  def saveGraphToNTriples(graph: Rdf#Graph, file: String): Unit = {
    toTripleRDD(graph).mapPartitions {
      case it =>
        toNTriples(it.toIterable).split("\n").iterator
    }.saveAsTextFile(file)
  }

  def loadGraphFromSequenceFile(file: String): Rdf#Graph = loadGraphFromSequenceFile(file + ".vertices", file + ".edges")

  def saveGraphToSequenceFile(graph:Rdf#Graph, file: String): Unit = saveGraphToSequenceFile(graph, file + ".vertices", file + ".edges")

  // TODO: Do sequenceFile I/O using Avro, more efficient
  def loadGraphFromSequenceFile(vertexFile: String, edgeFile: String): Rdf#Graph = {
    val vertices: RDD[(VertexId, Rdf#Node)] = sparkContext.objectFile(vertexFile)
    val edges: RDD[Edge[Rdf#URI]] = sparkContext.objectFile(edgeFile)
    SparkGraph(vertices, edges)
  }

  // TODO: Do sequenceFile I/O using Avro, more efficient
  def saveGraphToSequenceFile(graph:Rdf#Graph, vertexFile: String, edgeFile: String): Unit = {
    graph.vertices.saveAsObjectFile(vertexFile)
    graph.edges.saveAsObjectFile(edgeFile)
  }

  def makeGraph(triples: Iterable[Rdf#Triple]): Rdf#Graph = {
    val triplesRDD = sparkContext.parallelize(triples.toSeq)
    makeGraph(triplesRDD)
  }

  def makeGraph(triples: RDD[Rdf#Triple]): Rdf#Graph = {
    val spo: RDD[(Rdf#Node, (Rdf#URI, Rdf#Node))] = triples.map(fromTriple).map {
      case (s, p, o) => (s, (p, o))
    }

    val vertexIDs = spo.flatMap {
      case (s, (p, o)) =>
        Seq(s, p.asInstanceOf[Rdf#Node], o)
    }.zipWithUniqueId()

    val vertices: RDD[(VertexId, Rdf#Node)] = vertexIDs.map(v => (v._2, v._1))

    val subjectMappedEdges = spo.join(vertexIDs).map {
      case (s, ((p, o), sid)) =>
        (o, (sid, p))
    }

    val subjectObjectMappedEdges: RDD[Edge[Rdf#URI]] = subjectMappedEdges.join(vertexIDs).map {
      case (o, ((sid, p), oid)) =>
        Edge(sid, oid, p)
    }

    val subjectVertexIds: RDD[(VertexId, Option[Int])] = subjectObjectMappedEdges.map(x => (x.srcId, None))
    val objectVertexIds: RDD[(VertexId, Option[Int])] = subjectObjectMappedEdges.map(x => (x.dstId, None))

    val a = SparkGraph(vertices, subjectObjectMappedEdges)
    a
  }

  def makeHashedVertexGraph(triples: RDD[Rdf#Triple]): Rdf#Graph = {
    val spo: RDD[(Rdf#Node, Rdf#URI, Rdf#Node)] = triples.map {
      case Triple(s, p, o) => (s, p, o)
    }

    def hash(s: Rdf#Node) = MurmurHash3.stringHash(s.toString).toLong

    val vertices: RDD[(VertexId, Rdf#Node)] = spo.flatMap {
      case (s: Rdf#Node, p: Rdf#URI, o: Rdf#Node) =>
        Seq((hash(s), s), (hash(p), p), (hash(o), o))
    }

    val edges: RDD[Edge[Rdf#URI]] = spo.map {
      case (s: Rdf#Node, p: Rdf#URI, o: Rdf#Node) =>
        Edge(hash(s), hash(o), p)
    }

    SparkGraph(vertices, edges)
  }

  def toTripleRDD(graph: Rdf#Graph): RDD[Rdf#Triple] =
    graph.triplets.map{case x => Triple(x.srcAttr, x.attr, x.dstAttr)}

  def getTriples(graph: Rdf#Graph): Iterable[Rdf#Triple] =
    toTripleRDD(graph).toLocalIterator.toIterable

  // graph traversal

  def getObjectsRDD(graph: Rdf#Graph, subject: Rdf#Node, predicate: Rdf#URI): RDD[Rdf#Node] =
    findGraph(graph, toConcreteNodeMatch(subject), toConcreteNodeMatch(predicate), ANY).triplets.map(_.dstAttr)

  def getObjectsRDD(graph: Rdf#Graph, predicate: Rdf#URI): RDD[Rdf#Node] =
    findGraph(graph, ANY, toConcreteNodeMatch(predicate), ANY).triplets.map(_.dstAttr)

  def getPredicatesRDD(graph: Rdf#Graph, subject: Rdf#Node): RDD[Rdf#URI] =
    findGraph(graph, toConcreteNodeMatch(subject), ANY, ANY).triplets.map(_.attr)

  def getSubjectsRDD(graph: Rdf#Graph, predicate: Rdf#URI, obj: Rdf#Node): RDD[Rdf#Node] =
    findGraph(graph, ANY, toConcreteNodeMatch(predicate), toConcreteNodeMatch(obj)).triplets.map(_.srcAttr)

  def getSubjectsRDD(graph: Rdf#Graph, predicate: Rdf#URI): RDD[Rdf#Node] =
    findGraph(graph, ANY, toConcreteNodeMatch(predicate), ANY).triplets.map(_.srcAttr)

  // graph traversal

  def findGraph(graph: Rdf#Graph, subject: Rdf#NodeMatch, predicate: Rdf#NodeMatch, objectt: Rdf#NodeMatch): Rdf#Graph = {
    graph.subgraph({
      (triplet) =>
        matchNode(triplet.srcAttr, subject) && matchNode(triplet.attr, predicate) && matchNode(triplet.dstAttr, objectt)
    }, (_, _) => true)
  }

  def find(graph: Rdf#Graph, subject: Rdf#NodeMatch, predicate: Rdf#NodeMatch, objectt: Rdf#NodeMatch): Iterator[Rdf#Triple] =
    toTripleRDD(findGraph(graph, subject, predicate, objectt)).toLocalIterator

  // graph operations

  def union(graphs: Seq[Rdf#Graph]): Rdf#Graph = {
//    implicit val ct1 = ClassTag(implicitly[ClassTag[Rdf#Node]].runtimeClass)
//    implicit val ct2 = ClassTag(implicitly[ClassTag[Rdf#URI]].runtimeClass)

    graphs.reduce {
      (left: Rdf#Graph, right: Rdf#Graph) =>

        val newGraph = SparkGraph(left.vertices.union(right.vertices), left.edges.union(right.edges))
        newGraph.partitionBy(RandomVertexCut).groupEdges((attr1, attr2) => attr1)
    }
  }

  def intersection(graphs: Seq[Rdf#Graph]): Rdf#Graph =
    graphs.reduce {
      (left: Rdf#Graph, right: Rdf#Graph) =>
        val newGraph = SparkGraph(left.vertices.intersection(right.vertices), left.edges.intersection(right.edges))
        newGraph.partitionBy(RandomVertexCut).groupEdges( (attr1, attr2) => attr1 )
    }

  def difference(g1: Rdf#Graph, g2: Rdf#Graph): Rdf#Graph = {
    /// subtract triples; edge triplet intersection is collected into memory - is there a better way? Joining somehow?
    val matchingTriplets = g1.triplets.intersection(g2.triplets).collect().toSet
    g1.subgraph({
      (triplet) =>
        matchingTriplets.contains(triplet)
    }, (_, _) => true)
  }

  /**
   * Implement Spark algorithm for determining whether left and right are isomorphic
   */
  def isomorphism(left: Rdf#Graph, right: Rdf#Graph): Boolean = ???

  def graphSize(g: Rdf#Graph): Long = g.numEdges
}
