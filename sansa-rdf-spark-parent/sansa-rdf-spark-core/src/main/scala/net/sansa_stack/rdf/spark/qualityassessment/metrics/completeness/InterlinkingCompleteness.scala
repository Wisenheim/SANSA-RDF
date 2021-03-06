package net.sansa_stack.rdf.spark.qualityassessment.metrics.completeness

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.jena.graph.{ Triple, Node }
import org.apache.jena.sparql.core.Quad
import net.sansa_stack.rdf.spark.qualityassessment.dataset.DatasetUtils
import net.sansa_stack.rdf.spark.utils.Vocabularies
import net.sansa_stack.rdf.spark.io.NQuadReader
import shapeless.TypeCase

/*
 * This metric measures the interlinking completeness. Since any resource of a
 * dataset can be interlinked with another resource of a foreign dataset this
 * metric makes a statement about the ratio of interlinked resources to
 * resources that could potentially be interlinked.
 * 
 * An interlink here is assumed to be a statement like
 * 
 *   <local resource> <some predicate> <external resource>
 * 
 * or
 * 
 *   <external resource> <some predicate> <local resource>
 * 
 * Local resources are those that share the same URI prefix of the considered
 * dataset, external resources are those that don't.
 * 
 * Zaveri et. al [http://www.semantic-web-journal.net/system/files/swj414.pdf]
 */
object InterlinkingCompleteness extends Serializable {
  @transient var spark: SparkSession = _
  val prefixes = DatasetUtils.getPrefixes()

  def apply(dataset: RDD[Triple]): Long = {

    /*
   		* isIRI(?s) && internal(?s) && isIRI(?o) && external(?o)
    			union
   		  isIRI(?s) && external(?s) && isIRI(?o) && internal(?o)
   */
    println("triples")

    val Interlinked =
      dataset.filter(f =>
        f.getSubject.isURI() && isInternal(f.getSubject) && f.getObject.isURI() && isExternal(f.getObject))
        .union(
          dataset.filter(f =>
            f.getSubject.isURI() && isExternal(f.getSubject) && f.getObject.isURI() && isInternal(f.getObject)))

    Interlinked.cache()

    val numSubj = Interlinked.map(_.getSubject).distinct().count()
    val numObj = Interlinked.map(_.getSubject).distinct().count()

    val numResources = numSubj + numObj
    val numInterlinkedResources = Interlinked.count()

    val value = if (numResources > 0)
      numInterlinkedResources / numResources;
    else 0

    def dcatify() = "<addProperty>$value<Add[rp[ery>"

    value
  }

  /*
	*  Checks if a resource ?node is local
	*/
  def isInternal(node: Node) = prefixes.contains(if (node.isLiteral) node.getLiteralLexicalForm else node.toString())

  /*
	*  Checks if a resource ?node is local
	*/
  def isExternal(node: Node) = !prefixes.contains(if (node.isLiteral) node.getLiteralLexicalForm else node.toString())
}