package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.SimpleTermDefinition
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{EmptyNullOr, JsonLdOptions}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._

import scala.annotation.tailrec

/**
  * A cursor to navigate on the context term definitions. It allows to reach scoped contexts, if defined
  */
sealed abstract private[jsonld] class TermDefinitionCursor { self =>

  def value: EmptyNullOr[TermDefinition]

  def options: JsonLdOptions

  lazy val context: EmptyNullOr[Context] = value.flatMap(_.context)

  def unresolvedContext: EmptyNullOr[Context]

  def failed: Boolean

  def succeeded: Boolean = !failed

  def isTop: Boolean

  def typeScoped: Boolean

  protected lazy val propagate: Boolean = context.toOption.flatMap(_.propagate).getOrElse(prev.propagate)

  protected def tpe: Option[Uri]

  protected def term: Option[String]

  protected def prev: TermDefinitionCursor

  @tailrec
  private[context] def prevPropagate: TermDefinitionCursor =
    if (isTop) self
    else if (propagate) self
    else prev.prevPropagate

  def mergeContext(other: EmptyNullOr[Context]): TermDefinitionCursor =
    other match {
      case Empty => self
      case Null => EmbeddedCursor(self, Val(emptyDefinition.withContext(Val(Context(base = EmptyNullOr(options.base))))))
      case otherValue =>
        val ctx = context.flatMap(_.merge(otherValue)).onNull(otherValue).onEmpty(otherValue)
        EmbeddedCursor(self, Val(emptyDefinition.withContext(ctx)))
    }

  lazy val downEmpty: TermDefinitionCursor =
    ValueCursor(self, Val(emptyDefinition.withContext(context)), "", self.unresolvedContext)

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
    context.flatMap(ctx => ctx.find(term).map(ctx -> _)) match {
      case Empty         => FailedValueCursor(self, term)
      case Null          => self
      case Val((ctx, v)) => ValueCursor(self, Val(v.withContext(ctx.merge(v.context))), term, v.context)
    }

  private def downTypes(types: Seq[Uri]): TermDefinitionCursor =
    context.flatMap(ctx => EmptyNullOr(ctx.findFirst(types).map { case (uri, term) => (ctx, uri, term) })) match {
      case Empty              => FailedTypeScopedCursor(self, types)
      case Null               => self
      case Val((ctx, tpe, v)) => TypeScopedCursor(self, Val(v.withContext(ctx.merge(v.context))), tpe, v.context)
    }

  def or(other: TermDefinitionCursor): TermDefinitionCursor =
    if (failed) other
    else self

}

private[jsonld] object TermDefinitionCursor {

  private val emptyDefinition = SimpleTermDefinition(uri"urn:root:term")

  val empty: TermDefinitionCursor = TopCursor(Empty, JsonLdOptions.empty)

  final private case class TopCursor private[context] (value: EmptyNullOr[TermDefinition], options: JsonLdOptions)
      extends TermDefinitionCursor {
    val isTop: Boolean                             = true
    val failed: Boolean                            = false
    protected val prev: TermDefinitionCursor       = this
    val typeScoped: Boolean                        = false
    val term: Option[String]                       = None
    val tpe: Option[Uri]                           = None
    val unresolvedContext: EmptyNullOr[Context]    = context
    override protected lazy val propagate: Boolean = context.toOption.flatMap(_.propagate).getOrElse(true)
  }

  final private case class EmbeddedCursor private[context] (
      prev: TermDefinitionCursor,
      value: EmptyNullOr[TermDefinition]
  ) extends TermDefinitionCursor {
    val isTop: Boolean                             = false
    val failed: Boolean                            = false
    val typeScoped: Boolean                        = false
    val term: Option[String]                       = None
    val tpe: Option[Uri]                           = None
    val unresolvedContext: EmptyNullOr[Context]    = context
    override protected lazy val propagate: Boolean = context.toOption.flatMap(_.propagate).getOrElse(true)
    val options: JsonLdOptions                     = prev.options
  }

  final private case class FailedValueCursor private[context] (
      prev: TermDefinitionCursor,
      termValue: String
  ) extends TermDefinitionCursor {
    val isTop: Boolean                          = false
    val failed: Boolean                         = true
    val value: EmptyNullOr[TermDefinition]      = Empty
    val typeScoped: Boolean                     = false
    val term: Option[String]                    = Some(termValue)
    val tpe: Option[Uri]                        = None
    val unresolvedContext: EmptyNullOr[Context] = context
    val options: JsonLdOptions                  = prev.options
  }

  final private case class FailedTypeScopedCursor private[context] (
      prev: TermDefinitionCursor,
      terms: Seq[Uri]
  ) extends TermDefinitionCursor {
    val isTop: Boolean                          = false
    val failed: Boolean                         = true
    val value: EmptyNullOr[TermDefinition]      = Empty
    val typeScoped: Boolean                     = true
    val term: Option[String]                    = None
    val tpe: Option[Uri]                        = None
    val unresolvedContext: EmptyNullOr[Context] = context
    val options: JsonLdOptions                  = prev.options
  }

  final private case class TypeScopedCursor private[context] (
      prev: TermDefinitionCursor,
      value: EmptyNullOr[TermDefinition],
      termValue: Uri,
      unresolvedContext: EmptyNullOr[Context]
  ) extends TermDefinitionCursor {
    val isTop: Boolean                             = false
    val failed: Boolean                            = false
    val typeScoped: Boolean                        = true
    val term: Option[String]                       = None
    val tpe: Option[Uri]                           = Some(termValue)
    val options: JsonLdOptions                     = prev.options
    override protected lazy val propagate: Boolean = context.toOption.flatMap(_.propagate).getOrElse(false)
  }

  final private case class ValueCursor private[context] (
      prev: TermDefinitionCursor,
      value: EmptyNullOr[TermDefinition],
      termValue: String,
      unresolvedContext: EmptyNullOr[Context]
  ) extends TermDefinitionCursor {
    val isTop: Boolean         = false
    val failed: Boolean        = false
    val typeScoped: Boolean    = false
    val term: Option[String]   = Some(termValue)
    val tpe: Option[Uri]       = None
    val options: JsonLdOptions = prev.options

  }

  final def apply(
      value: EmptyNullOr[TermDefinition]
  )(implicit options: JsonLdOptions = JsonLdOptions.empty): TermDefinitionCursor =
    TopCursor(value, options)

  final def fromCtx(ctx: EmptyNullOr[Context])(implicit options: JsonLdOptions): TermDefinitionCursor =
    apply(Val(emptyDefinition.withContext(ctx)))

}
