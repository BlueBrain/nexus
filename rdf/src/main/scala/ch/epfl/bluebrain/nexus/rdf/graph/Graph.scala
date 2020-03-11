package ch.epfl.bluebrain.nexus.rdf.graph

import cats.Eq
import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Node
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode}
import ch.epfl.bluebrain.nexus.rdf.graph.Graph._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.{Uri, Url}
import ch.epfl.bluebrain.nexus.rdf.iri.Path.Segment

/**
  * A rooted RDF graph. All vertices except the root are existential. Operations like append or prepend use the root to
  * generate a relationship as a triple between the graph and the node / graph that gets appended or prepended.
  */
sealed abstract class Graph extends Product with Serializable {

  /**
    * The graph root. It represents the "main" node of the graph such that traversals have a default starting point.
    */
  def root: Node

  /**
    * The collection of triples that make up this graph.
    */
  def triples: Set[Triple]

  /**
    * Adds a new triple (s, p, this.node) to this graph yielding a new one. The provided subject becomes the new graph
    * root.
    */
  def ::(subjectAndPredicate: (IriOrBNode, IriNode)): Graph = this match {
    case OptionalGraph(None) => this
    case _                   => prepend(Graph(subjectAndPredicate._1), subjectAndPredicate._2)
  }

  /**
    * Merges this graph with the provided one and additionally generates a new triple (g.root, predicate, this.root)
    * that represents a relationship between the two sub-graphs. The root of the resulting graph is `g.root`.
    */
  def prepend(g: Graph, predicate: IriNode): Graph = this match {
    case SetGraph(_, graphs) =>
      g.root match {
        case s: IriOrBNode =>
          Graph(s, g.triples ++ triples ++ graphs.map(e => (s, predicate, e.root)))
        case _ =>
          Graph(g.root, g.triples ++ triples)
      }
    case OptionalGraph(None) => g
    case OptionalGraph(Some(graph)) =>
      graph.prepend(g, predicate)
    case _ =>
      g match {
        case OptionalGraph(None) => this
        case _ =>
          g.root match {
            case s: IriOrBNode =>
              val link = (s, predicate, root)
              Graph(g.root, g.triples ++ triples + link)
            case _ =>
              Graph(g.root, g.triples ++ triples)
          }
      }
  }

  /**
    * Merges this graph with the provided one and additionally generates a new triple (this.root, predicate, g.root)
    * that represents a relationship between the two subgraphs. The root of the resulting graph is `this.root`.
    */
  def append(predicate: IriNode, g: Graph): Graph =
    g.prepend(this, predicate)

  /**
    * Adds a new triple (this.root, p, o) to this graph yielding a new one. The root is retained.
    */
  def append(p: IriNode, o: Node): Graph =
    this match {
      case OptionalGraph(None) => this
      case _ =>
        root match {
          case ibn: IriOrBNode => this + ((ibn, p, o))
          case _               => this
        }
    }

  /**
    * Adds the provided triple to the graph yielding a new one.
    */
  def +(triple: Triple): Graph =
    Graph(root, triples + triple)

  /**
    * Adds the provided triples to the graph yielding a new one.
    */
  def ++(triples: Set[Triple]): Graph =
    Graph(root, this.triples ++ triples)

  /**
    * Merges the this graph with that graph while retaining the root.
    */
  def ++(that: Graph): Graph =
    ++(that.triples)

  /**
    * Removes a specific triple from the graph if it exists.
    */
  def -(triple: Triple): Graph =
    Graph(root, triples - triple)

  /**
    * Removes the provided triples from the graph.
    */
  def --(triples: Set[Triple]): Graph =
    Graph(root, this.triples -- triples)

  /**
    * Removes the triples of that graph from this graph.
    */
  def --(that: Graph): Graph =
    --(that.triples)

  /**
    * The set of nodes in subject position.
    */
  def subjects: Set[IriOrBNode] =
    triples.map(_._1)

  /**
    * The set of predicates.
    */
  def predicates: Set[IriNode] =
    triples.map(_._2)

  /**
    * The set of nodes in object position.
    */
  def objects: Set[Node] =
    triples.map(_._3)

  /**
    * Returns a subgraph retaining all the triples that satisfy the provided predicate.
    */
  def filter(p: Triple => Boolean): Graph =
    Graph(root, triples.filter(p))

  /**
    * Returns a triple matching the predicate if found.
    */
  def find(p: Triple => Boolean): Option[Triple] =
    triples.find(p)

  /**
    * Replaces this graph root.
    */
  def withRoot(root: Node): Graph =
    Graph(root, triples)

  /**
    * Replaces a node in the graph.
    */
  def replaceNode(target: IriOrBNode, value: IriOrBNode): Graph = this match {
    case _: SingleNodeGraph if target == root => SingleNodeGraph(value)
    case _: SingleNodeGraph                   => this
    case OptionalGraph(Some(g))               => OptionalGraph(Some(g.replaceNode(target, value)))
    case g @ OptionalGraph(None)              => g
    case SetGraph(_, graphs) =>
      val newNode = if (root == target) value else root
      SetGraph(newNode, graphs.map(_.replaceNode(target, value)))
    case _: MultiNodeGraph =>
      val newNode = if (root == target) value else root
      MultiNodeGraph(newNode, triples.map {
        case (`target`, p, `target`) => (value, p, value)
        case (`target`, p, o)        => (value, p, o)
        case (s, p, `target`)        => (s, p, value)
        case default                 => default
      })
  }

  /**
    * Folds over the collection of triples.
    */
  def foldLeft[Z](z: Z)(f: (Z, (IriOrBNode, IriNode, Node)) => Z): Z =
    triples.foldLeft(z)(f)

  /**
    * Selects all nodes in object position that form triples with the provided subject and predicate.
    */
  def select(s: IriOrBNode, p: IriNode): Set[Node] =
    spToO.getOrElse((s, p), Set.empty)

  /**
    * Selects all nodes in subject position that form triples with the provided object and predicate.
    */
  def selectReverse(o: Node, p: IriNode): Set[IriOrBNode] =
    opToS.getOrElse((o, p), Set.empty)

  /**
    * The default graph cursor.
    */
  def cursor: Cursor =
    Cursor(this)

  /**
    * An n-triples representation of this graph.
    */
  def ntriples: String =
    triples
      .foldLeft(new StringBuilder) {
        case (b, (s, p, o)) =>
          b.append(s.show).append(" ").append(p.show).append(" ").append(o.show).append(" .\n")
      }
      .toString

  private[rdf] lazy val spToO: Map[(IriOrBNode, IriNode), Set[Node]] =
    triples.groupMap({ case (s, p, _) => (s, p) })({ case (_, _, o) => o })

  private[rdf] lazy val opToS: Map[(Node, IriNode), Set[IriOrBNode]] =
    triples.groupMap({ case (_, p, o) => (o, p) })({ case (s, _, _) => s })

  private val dotNonEscapedStringRegex = {
    val alphanum = "a-zA-Z\u0080-\u00ff_"
    s"[$alphanum][${alphanum}0-9]*".r
  }
  private val dotNumeralRegex = "[-]?(.[0-9]+|[0-9]+(.[0-9]*)?)".r

  /**
    * Returns a DOT representation of this graph.
    *
    * @param prefixMappings     prefix mappings to apply to IRIs.
    * @param sequenceBlankNodes whether to replace blank node IDs with sequential identifiers.
    * @param stripPrefixes      whether to strip prefixes from IRIs.
    * @return the DOT representation as [[String]].
    */
  def dot(
      prefixMappings: Map[Uri, String] = Map.empty,
      sequenceBlankNodes: Boolean = true,
      stripPrefixes: Boolean = false
  ): String = {

    def escapeChar(c: Char): String = c match {
      case '"' => "\\\""
      case x   => x.toString
    }

    def escape(str: String): String =
      str.flatMap(escapeChar(_: Char))

    def applyOrStripPrefix(iri: Uri): String =
      prefixMappings
        .get(iri)
        .orElse(
          prefixMappings
            .find {
              case (prefix, _) if iri.iriString.startsWith(prefix.iriString) => true
              case _                                                         => false
            }
            .map {
              case (prefix, mapping) => s"$mapping:${iri.iriString.stripPrefix(prefix.iriString)}"
            }
        )
        .getOrElse {
          if (stripPrefixes)
            iri match {
              case Url(_, _, _, _, Some(fragment))  => fragment.iriString
              case Url(_, _, Segment(seg, _), _, _) => seg
              case _                                => iri.iriString
            }
          else iri.iriString

        }

    def escapeAndQuote(node: Node, bNodeIds: Map[String, String] = Map.empty) = {
      val id = node match {
        case IriNode(iri)                     => applyOrStripPrefix(iri)
        case BNode(bId) if sequenceBlankNodes => bNodeIds.get(bId).map(i => s"_:b$i").getOrElse(bId)
        case _                                => node.toString
      }
      if (dotNonEscapedStringRegex.matches(id) || dotNumeralRegex.matches(id))
        id
      else
        s""""${escape(id)}""""

    }

    def updateBNodeId(bNodeIds: Map[String, String], lastBNodeId: Int, bIds: String*): (Map[String, String], Int) =
      bIds.foldLeft((bNodeIds, lastBNodeId)) {
        case ((bNIds, lastId), bId) =>
          bNIds.get(bId) match {
            case Some(_) => (bNIds, lastId)
            case None    => (bNIds.updated(bId, (lastId + 1).toString), lastId + 1)
          }

      }

    def updateBNodeIds(triple: Triple, bNodeIds: Map[String, String], lastBNodeId: Int): (Map[String, String], Int) =
      (sequenceBlankNodes, triple) match {
        case (false, _)                         => (bNodeIds, lastBNodeId)
        case (_, (BNode(bId1), _, BNode(bId2))) => updateBNodeId(bNodeIds, lastBNodeId, bId1, bId2)
        case (_, (BNode(bId), _, _))            => updateBNodeId(bNodeIds, lastBNodeId, bId)
        case (_, (_, _, BNode(bId)))            => updateBNodeId(bNodeIds, lastBNodeId, bId)
        case _                                  => (bNodeIds, lastBNodeId)
      }

    triples
      .foldLeft((new StringBuilder(s"""digraph ${escapeAndQuote(root)} {\n"""), Map.empty[String, String], 0)) {
        case ((b, bNodeIds, lastBNodeId), (s, p, o)) =>
          val (updatedBNodeIds, updatedLastBNodeId) = updateBNodeIds((s, p, o), bNodeIds, lastBNodeId)
          b.append("  ")
            .append(escapeAndQuote(s, updatedBNodeIds))
            .append(" -> ")
            .append(escapeAndQuote(o, updatedBNodeIds))
            .append(" [label = ")
            .append(escapeAndQuote(p))
            .append("]\n")
          (b, updatedBNodeIds, updatedLastBNodeId)
      }
      ._1
      .append("}")
      .toString
  }

}

object Graph {

  /**
    * Constructs a new [[Graph]] from the provided root node and set of triples.
    */
  final def apply(root: Node, triples: Set[Triple] = Set.empty): Graph =
    if (triples.isEmpty) SingleNodeGraph(root)
    else MultiNodeGraph(root, triples)

  final private[rdf] case class SingleNodeGraph(root: Node) extends Graph {
    override val triples: Set[(IriOrBNode, IriNode, Node)] = Set.empty
  }

  final private[rdf] case class SetGraph(root: Node, graphs: Set[Graph]) extends Graph {
    override lazy val triples: Set[Triple] =
      graphs.foldLeft(Set.empty[Triple])(_ ++ _.triples)
  }

  final private[rdf] case class OptionalGraph(graph: Option[Graph]) extends Graph {
    override lazy val root: Node = graph.map(_.root).getOrElse(BNode())
    override lazy val triples: Set[Triple] =
      graph.map(_.triples).getOrElse(Set.empty)
  }

  final private[rdf] case class MultiNodeGraph(root: Node, triples: Set[Triple]) extends Graph

  type Triple = (IriOrBNode, IriNode, Node)

  implicit final val graphEq: Eq[Graph] = Eq.instance {
    case (OptionalGraph(None), OptionalGraph(None)) => true
    case (left, right)                              => left.root == right.root && left.triples == right.triples
  }

}
