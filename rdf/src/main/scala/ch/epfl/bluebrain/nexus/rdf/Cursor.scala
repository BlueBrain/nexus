package ch.epfl.bluebrain.nexus.rdf

import ch.epfl.bluebrain.nexus.rdf.Node.{IriNode, IriOrBNode, Literal}

import scala.annotation.tailrec

/**
  * A zipper that represents a position in a Graph and supports navigation. The implementation is inspired from the
  * circe project (https://github.com/circe/circe) but it's limited to traversals only, no modifications.
  *
  * @param lastCursor the previous cursor
  * @param lastOp the previous operation
  * @param g the target graph
  */
sealed abstract class Cursor(private val lastCursor: Cursor, private val lastOp: CursorOp, private val g: Graph) {

  /**
    * The current node in the graph
    */
  def focus: Option[Node]

  /**
    * If the focus is a set of nodes return its elements.
    */
  def values: Option[Set[Node]]

  /**
    * If the focus is a set of nodes return a set of cursors positioned on each of the nodes.
    */
  def cursors: Option[Set[Cursor]]

  /**
    * If the focus is a set of nodes with a single value, return the cursor for the underlying node. Otherwise return
    * a failed cursor.
    */
  def narrow: Cursor

  /**
    * Indicate whether this cursor represents the result of a successful operation.
    */
  def succeeded: Boolean

  /**
    * Indicate whether this cursor represents the result of a failed operation.
    */
  def failed: Boolean = !succeeded

  /**
    * Return the top cursor with an empty history.
    */
  def top: Cursor =
    Cursor(g)

  /**
    * Move the focus to the parent.
    */
  def parent: Cursor =
    lastCursor

  /**
    * Move the focus to the `subject` node that satisfies the relationship `subject p focus`. Fails if there are no
    * matches or more than one.
    */
  def up(p: IriNode): Cursor

  /**
    * Move the focus to all `subject` nodes that satisfy the relationship `subject p focus`.
    */
  def upSet(p: IriNode): Cursor

  /**
    * Move the focus to the `object` node that satisfies the relationship `focus p object`. Fails if there are no
    * matches or more than one.
    */
  def down(p: IriNode): Cursor

  /**
    * Move the focus to all `object` nodes that satisfy the relationship `focus p object`.
    */
  def downSet(p: IriNode): Cursor

  /**
    * The operations that have been performed so far from the first to the more recent.
    */
  def history: List[CursorOp] = {
    @tailrec
    @SuppressWarnings(Array("NullParameter"))
    def inner(cursor: Cursor, acc: List[CursorOp]): List[CursorOp] =
      if (cursor.lastCursor == null) acc
      else inner(cursor.lastCursor, cursor.lastOp :: acc)
    inner(this, List.empty)
  }

  /**
    * Attempts to decode the current focus or values a an `A`.
    */
  def as[A](implicit A: GraphDecoder[A]): GraphDecoder.Result[A] =
    A(this)

  /**
    * Returns the Graph associated with this cursor.
    */
  def graph: Graph = g
}

object Cursor {

  /**
    * Creates the default cursor for the argument Graph by positioning the cursor on the graph root node.
    */
  @SuppressWarnings(Array("NullParameter"))
  final def apply(graph: Graph): Cursor =
    NodeCursor(graph.root, null, null, graph)

  final private[this] case class NodeCursor(node: Node, lastCursor: Cursor, lastOp: CursorOp, g: Graph)
      extends Cursor(lastCursor, lastOp, g) {

    override def focus: Option[Node]          = Some(node)
    override def values: Option[Set[Node]]    = None
    override def cursors: Option[Set[Cursor]] = None
    override def narrow: Cursor               = this
    override def succeeded: Boolean           = true

    override def up(p: IriNode): Cursor = {
      val ss = g.selectReverse(node, p)
      ss.take(2).toList match {
        case s :: Nil => NodeCursor(s, this, CursorOp.Up(p), g)
        case _        => FailedCursor(this, CursorOp.Up(p), g)
      }
    }

    override def upSet(p: IriNode): Cursor = {
      val ss = g.selectReverse(node, p)
      val cs = ss.map(s => NodeCursor(s, this, CursorOp.UpSet(p), g))
      SetCursor(cs, this, CursorOp.UpSet(p), g)
    }

    override def down(p: IriNode): Cursor =
      node match {
        case s: IriOrBNode =>
          val os = g.select(s, p)
          os.take(2).toList match {
            case o :: Nil => NodeCursor(o, this, CursorOp.Down(p), g)
            case _        => FailedCursor(this, CursorOp.Down(p), g)
          }
        case _: Literal    => FailedCursor(this, CursorOp.Down(p), g)
      }

    override def downSet(p: IriNode): Cursor =
      node match {
        case s: IriOrBNode =>
          val os = g.select(s, p)
          val cs = os.map(o => NodeCursor(o, this, CursorOp.DownSet(p), g))
          SetCursor(cs, this, CursorOp.DownSet(p), g)
        case _: Literal    => FailedCursor(this, CursorOp.DownSet(p), g)
      }

  }

  final private[this] case class FailedCursor(lastCursor: Cursor, lastOp: CursorOp, g: Graph)
      extends Cursor(lastCursor, lastOp, g) {
    override def focus: Option[Node]          = None
    override def values: Option[Set[Node]]    = None
    override def cursors: Option[Set[Cursor]] = None
    override def narrow: Cursor               = this
    override def succeeded: Boolean           = false
    override def up(p: IriNode): Cursor       = FailedCursor(this, CursorOp.Up(p), g)
    override def upSet(p: IriNode): Cursor    = FailedCursor(this, CursorOp.UpSet(p), g)
    override def down(p: IriNode): Cursor     = FailedCursor(this, CursorOp.Down(p), g)
    override def downSet(p: IriNode): Cursor  = FailedCursor(this, CursorOp.DownSet(p), g)
  }

  final private[this] case class SetCursor(cursorSet: Set[NodeCursor], lastCursor: Cursor, lastOp: CursorOp, g: Graph)
      extends Cursor(lastCursor, lastOp, g) {

    override def focus: Option[Node] = None

    override def values: Option[Set[Node]] = {
      val nodes = cursorSet.foldLeft(Set.empty[Node]) {
        case (set, c) =>
          c.focus match {
            case Some(n) => set + n
            case None    => set
          }
      }
      Some(nodes)
    }

    override def cursors: Option[Set[Cursor]] =
      Some(cursorSet.asInstanceOf[Set[Cursor]])

    override def narrow: Cursor =
      cursorSet.take(2).toList match {
        case c :: Nil => c
        case _        => FailedCursor(this, CursorOp.Narrow, g)
      }

    override def succeeded: Boolean = true

    override def up(p: IriNode): Cursor = {
      val cs = cursorSet.foldLeft(Set.empty[NodeCursor]) {
        case (set, c) =>
          val ss = g.selectReverse(c.node, p)
          ss.take(2).toList match {
            case s :: Nil => set + NodeCursor(s, c, CursorOp.Up(p), g)
            case _        => set
          }
      }
      SetCursor(cs, this, CursorOp.Up(p), g)
    }

    override def upSet(p: IriNode): Cursor = {
      val cs = cursorSet.foldLeft(Set.empty[NodeCursor]) {
        case (set, c) =>
          val ss = g.selectReverse(c.node, p)
          set union ss.map(s => NodeCursor(s, c, CursorOp.UpSet(p), g))
      }
      SetCursor(cs, this, CursorOp.UpSet(p), g)
    }

    override def down(p: IriNode): Cursor = {
      val cs = cursorSet.foldLeft(Set.empty[NodeCursor]) {
        case (set, c) =>
          c.node match {
            case s: IriOrBNode =>
              val os = g.select(s, p)
              os.take(2).toList match {
                case o :: Nil => set + NodeCursor(o, c, CursorOp.Down(p), g)
                case _        => set
              }
            case _             => set
          }
      }
      SetCursor(cs, this, CursorOp.Down(p), g)
    }

    override def downSet(p: IriNode): Cursor = {
      val cs = cursorSet.foldLeft(Set.empty[NodeCursor]) {
        case (set, c) =>
          c.node match {
            case s: IriOrBNode =>
              val os = g.select(s, p)
              set union os.map(o => NodeCursor(o, c, CursorOp.Down(p), g))
            case _             => set
          }
      }
      SetCursor(cs, this, CursorOp.Down(p), g)
    }
  }
}
