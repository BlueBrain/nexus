package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.NoneNullOr
import ch.epfl.bluebrain.nexus.rdf.jsonld.NoneNullOr._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.SimpleTermDefinition
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._

/**
  * A cursor to navigate on the context term definitions. It allows to reach scoped contexts, if defined
  */
sealed abstract class TermDefinitionCursor { self =>

  def value: NoneNullOr[TermDefinition]

  lazy val context: NoneNullOr[Context] = value.flatMap(_.context)

  def unresolvedContext: NoneNullOr[Context]

  def failed: Boolean

  def succeeded: Boolean = !failed

  def isTop: Boolean

  def typeScoped: Boolean

  protected lazy val propagate: Boolean = context.toOption.flatMap(_.propagate).getOrElse(prev.propagate)

  def top: TermDefinitionCursor

  protected def tpe: Option[Uri]

  protected def term: Option[String]

  protected def prev: TermDefinitionCursor

  private[context] def prevPropagate: TermDefinitionCursor =
    if(isTop) self
    else if(propagate) self
    else prev.prevPropagate

  def mergeContext(other: NoneNullOr[Context]): TermDefinitionCursor =
    other match {
      case Empty => self
      case otherValue =>
        val ctx = context.flatMap(_.merge(otherValue)).onNull(otherValue).onNone(otherValue)
        EmbeddedCursor(top, self, Val(emptyDefinition.withContext(ctx)))
    }

  lazy val downEmpty: TermDefinitionCursor =
    ValueCursor(top, self, Val(emptyDefinition.withContext(context)), "", self.unresolvedContext)

  def down(term: String, types: Seq[Uri] = Seq.empty): TermDefinitionCursor =
    if (succeeded && !propagate && typeScoped)
      prevPropagate.down(term, types)
    else if (succeeded && !propagate && !unresolvedContext.exists(_.terms.contains(term)))
      prevPropagate.down(term, types)
    else
      downTypes(types).downTerm(term) or downTerm(term) or downTypes(types) or downEmpty

  def downId(types: Seq[Uri], revertOnTypeScoped: Boolean = true): TermDefinitionCursor =
    if (succeeded && !propagate && typeScoped && revertOnTypeScoped) prev.downId(types)
    else downTypes(types) or downEmpty

  private[context] def downTerm(term: String): TermDefinitionCursor =
    context.flatMap(ctx => NoneNullOr(ctx.find(term)).map(ctx -> _)) match {
      case Empty         => FailedValueCursor(top, self, term)
      case Null          => self
      case Val((ctx, v)) => ValueCursor(top, self, Val(v.withContext(ctx.merge(v.context))), term, v.context)
    }

  private def downTypes(types: Seq[Uri]): TermDefinitionCursor =
    context.flatMap(ctx => NoneNullOr(ctx.findFirst(types).map { case (uri, term) => (ctx, uri, term) })) match {
      case Empty              => FailedTypeScopedCursor(top, self, types)
      case Null               => self
      case Val((ctx, tpe, v)) => TypeScopedCursor(top, self, Val(v.withContext(ctx.merge(v.context))), tpe, v.context)
    }

  def or(other: TermDefinitionCursor): TermDefinitionCursor =
    if (failed) other
    else self

}

object TermDefinitionCursor {

  private val emptyDefinition = SimpleTermDefinition(uri"urn:root:term")

  val empty: TermDefinitionCursor = TopCursor(Empty)

  final private case class TopCursor private[context] (value: NoneNullOr[TermDefinition]) extends TermDefinitionCursor {
    val isTop: Boolean                         = true
    val failed: Boolean                        = false
    val top: TopCursor                         = this
    protected val prev: TermDefinitionCursor   = this
    val typeScoped: Boolean                    = false
    val term: Option[String]                   = None
    val tpe: Option[Uri]                       = None
    val unresolvedContext: NoneNullOr[Context] = context
    override protected lazy val propagate: Boolean = context.toOption.flatMap(_.propagate).getOrElse(true)
  }

  final private case class EmbeddedCursor private[context] (
      top: TermDefinitionCursor,
      prev: TermDefinitionCursor,
      value: NoneNullOr[TermDefinition]
  ) extends TermDefinitionCursor {
    val isTop: Boolean                         = false
    val failed: Boolean                        = false
    val typeScoped: Boolean                    = false
    val term: Option[String]                   = None
    val tpe: Option[Uri]                       = None
    val unresolvedContext: NoneNullOr[Context] = context
    override protected lazy val propagate: Boolean = context.toOption.flatMap(_.propagate).getOrElse(true)
  }

  final private case class FailedValueCursor private[context] (
      top: TermDefinitionCursor,
      prev: TermDefinitionCursor,
      termValue: String
  ) extends TermDefinitionCursor {
    val isTop: Boolean                         = false
    val failed: Boolean                        = true
    val value: NoneNullOr[TermDefinition]      = Empty
    val typeScoped: Boolean                    = false
    val term: Option[String]                   = Some(termValue)
    val tpe: Option[Uri]                       = None
    val unresolvedContext: NoneNullOr[Context] = context
  }

  final private case class FailedTypeScopedCursor private[context] (
      top: TermDefinitionCursor,
      prev: TermDefinitionCursor,
      terms: Seq[Uri]
  ) extends TermDefinitionCursor {
    val isTop: Boolean                         = false
    val failed: Boolean                        = true
    val value: NoneNullOr[TermDefinition]      = Empty
    val typeScoped: Boolean                    = true
    val term: Option[String]                   = None
    val tpe: Option[Uri]                       = None
    val unresolvedContext: NoneNullOr[Context] = context

  }

  final private case class TypeScopedCursor private[context] (
      top: TermDefinitionCursor,
      prev: TermDefinitionCursor,
      value: NoneNullOr[TermDefinition],
      termValue: Uri,
      unresolvedContext: NoneNullOr[Context]
  ) extends TermDefinitionCursor {
    val isTop: Boolean                             = false
    val failed: Boolean                            = false
    val typeScoped: Boolean                        = true
    val term: Option[String]                       = None
    val tpe: Option[Uri]                           = Some(termValue)
    override protected lazy val propagate: Boolean = context.toOption.flatMap(_.propagate).getOrElse(false)
  }

  final private case class ValueCursor private[context] (
      top: TermDefinitionCursor,
      prev: TermDefinitionCursor,
      value: NoneNullOr[TermDefinition],
      termValue: String,
      unresolvedContext: NoneNullOr[Context]
  ) extends TermDefinitionCursor {
    val isTop: Boolean       = false
    val failed: Boolean      = false
    val typeScoped: Boolean  = false
    val term: Option[String] = Some(termValue)
    val tpe: Option[Uri]     = None
  }

  final def apply(value: NoneNullOr[TermDefinition]): TermDefinitionCursor =
    TopCursor(value)

  final def fromCtx(ctx: NoneNullOr[Context]): TermDefinitionCursor =
    apply(Val(emptyDefinition.withContext(ctx)))

  final def firstSuccess(cursors: Seq[TermDefinitionCursor]): TermDefinitionCursor =
    cursors.foldLeft(empty) {
      case (`empty`, current)  => current
      case (previous, current) => previous or current
    }

}
