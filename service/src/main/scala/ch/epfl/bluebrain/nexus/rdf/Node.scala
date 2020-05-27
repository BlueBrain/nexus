package ch.epfl.bluebrain.nexus.rdf

import java.util.UUID

import cats.implicits._
import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.Literal._
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import org.parboiled2.CharPredicate._
import org.parboiled2._

/**
  * Top level type for RDF nodes.
  */
sealed abstract class Node extends Product with Serializable {

  /**
    * @return true if this node is a blank node, false otherwise
    */
  def isBlank: Boolean

  /**
    * @return true if this node is an Iri node, false otherwise
    */
  def isIri: Boolean

  /**
    * @return true if this node is a literal node, false otherwise
    */
  def isLiteral: Boolean

  /**
    * @return Some(this) if this is a blank node, None otherwise
    */
  def asBlank: Option[BNode]

  /**
    * @return Some(this) if this is an Iri node, None otherwise
    */
  def asIri: Option[IriNode]

  /**
    * @return Some(this) if this is a literal node, None otherwise
    */
  def asLiteral: Option[Literal]
}

object Node extends PrimitiveNodeConversions with StandardNodeConversions with FunctionConversions with RdfConversions {

  /**
    * @return a new blank node with a random unique identifier
    */
  final def blank: BNode =
    BNode()

  /**
    * Attempts to create a new blank node with the provided identifier.  An identifier must contain only alphanumeric
    * characters or ''-'' or ''_''.
    *
    * @param id the blank node identifier
    * @return Right(BNode) if the identifier matches the character restrictions, Left(error) otherwise
    */
  final def blank(id: String): Either[String, BNode] =
    BNode(id)

  /**
    * Lifts the argument iri to an IriNode.
    *
    * @param iri the underlying iri
    */
  final def iri(iri: AbsoluteIri): IriNode =
    IriNode(iri)

  /**
    * Attempts to create a new IriNode from the argument string.  The provided value is checked against the
    * constraints of an absolute iri and normalized.
    *
    * @param string a string iri
    * @return Right(IriNode) if the argument matches the absolute iri syntax, Left(error) otherwise
    */
  final def iri(string: String): Either[String, IriNode] =
    Iri.absolute(string).map(IriNode.apply)

  /**
    * Creates a new Literal node from the arguments.
    *
    * @param lexicalForm the literal lexical form
    * @param dataType    the data type of the literal
    */
  final def literal(lexicalForm: String, dataType: AbsoluteIri): Literal =
    Literal(lexicalForm, dataType)

  /**
    * Creates a new string literal.  String literals have the ''http://www.w3.org/2001/XMLSchema#string'' data type.
    *
    * @param string the underlying string
    */
  final def literal(string: String): Literal =
    Literal(string)

  /**
    * Creates a new string literal tagged with the argument language tag.  Tagged string literals have the
    * ''http://www.w3.org/1999/02/22-rdf-syntax-ns#langString'' data type.
    *
    * @param string      the underlying string
    * @param languageTag the language tag
    */
  final def literal(string: String, languageTag: LanguageTag): Literal =
    Literal(string, languageTag)

  /**
    * Creates a new Boolean literal of ''http://www.w3.org/2001/XMLSchema#boolean'' data type.
    *
    * @param value the underlying boolean value
    */
  final def literal(value: Boolean): Literal =
    Literal(value)

  /**
    * Creates a new Byte literal of ''http://www.w3.org/2001/XMLSchema#byte'' data type.
    *
    * @param value the underlying byte value
    */
  final def literal(value: Byte): Literal =
    Literal(value)

  /**
    * Creates a new Integer literal of ''http://www.w3.org/2001/XMLSchema#integer'' data type.
    *
    * @param value the underlying integer value
    */
  final def literal(value: Int): Literal =
    Literal(value)

  /**
    * Creates a new Short literal of ''http://www.w3.org/2001/XMLSchema#short'' data type.
    *
    * @param value the underlying short value
    */
  final def literal(value: Short): Literal =
    Literal(value)

  /**
    * Creates a new Long literal of ''http://www.w3.org/2001/XMLSchema#long'' data type.
    *
    * @param value the underlying long value
    */
  final def literal(value: Long): Literal =
    Literal(value)

  /**
    * Creates a new Float literal of ''http://www.w3.org/2001/XMLSchema#float'' data type.
    *
    * @param value the underlying float value
    */
  final def literal(value: Float): Literal =
    Literal(value)

  /**
    * Creates a new Decimal literal of ''http://www.w3.org/2001/XMLSchema#double'' data type.
    *
    * @param value the underlying double value
    */
  final def literal(value: Double): Literal =
    Literal(value)

  /**
    * Top level type for nodes that can be used in subject or object position of an RDF triple.
    */
  sealed abstract class IriOrBNode extends Node {
    override def isLiteral: Boolean         = false
    override def asLiteral: Option[Literal] = None
  }

  object IriOrBNode {
    implicit final def iriOrBNodeShow(implicit I: Show[IriNode], B: Show[BNode]): Show[IriOrBNode] = Show.show {
      case v: IriNode => I.show(v)
      case v: BNode   => B.show(v)
    }
    implicit final def iriOrBNodeEq: Eq[IriOrBNode] = Eq.fromUniversalEquals
  }

  /**
    * A blank node.
    *
    * @param id the node identifier
    */
  final case class BNode private[rdf] (id: String) extends IriOrBNode {
    override def isBlank: Boolean       = true
    override def isIri: Boolean         = false
    override def asBlank: Option[BNode] = Some(this)
    override def asIri: Option[IriNode] = None
    override def toString: String       = s"_:$id"
  }

  object BNode {

    /**
      * @return a new blank node with a random unique identifier
      */
    final def apply(): BNode =
      new BNode(UUID.randomUUID().toString)

    /**
      * Attempts to create a new blank node with the provided identifier.  An identifier must contain only alphanumeric
      * characters or ''-'' or ''_''.
      *
      * @param id the blank node identifier
      * @return Right(BNode) if the identifier matches the character restrictions, Left(error) otherwise
      */
    final def apply(id: String): Either[String, BNode] = {
      import org.parboiled2.Parser.DeliveryScheme.Either
      val formatter = new ErrorFormatter(showExpected = false, showTraces = false)
      new BNodeParser(id).bnode
        .run()
        .map(_ => new BNode(id))
        .leftMap(_.format(id, formatter))
    }

    implicit final val bnodeShow: Show[BNode] = Show.show(_.toString)
    implicit final val bnodeEq: Eq[BNode]     = Eq.fromUniversalEquals

    //noinspection TypeAnnotation
    // format: off
    @SuppressWarnings(Array("MethodNames"))
    private class BNodeParser(val input: ParserInput) extends Parser {
      def bnode = rule { AlphaNum ~ zeroOrMore(AlphaNum ++ "-_") ~ EOI }
    }
  }

  /**
    * An Iri node.
    *
    * @param value the underlying iri value
    */
  final case class IriNode(value: AbsoluteIri) extends IriOrBNode {
    override def isBlank: Boolean       = false
    override def isIri: Boolean         = true
    override def asBlank: Option[BNode] = None
    override def asIri: Option[IriNode] = Some(this)
    override def toString: String = value.show
  }

  object IriNode {
    final implicit def iriNodeShow(implicit is: Show[AbsoluteIri]): Show[IriNode] =
      Show.show(i => s"<${is.show(i.value)}>"
      )

    final implicit val iriNodeEq: Eq[IriNode] = Eq.fromUniversalEquals
  }

  /**
    * A literal node.
    *
    * @param lexicalForm the lexical form of its value
    * @param dataType    the data type
    * @param languageTag the optional language tag if the value is a tagged string
    */
  final case class Literal private[rdf] (
      lexicalForm: String,
      dataType: AbsoluteIri,
      languageTag: Option[LanguageTag] = None
  ) extends Node {
    override def isBlank: Boolean           = false
    override def isIri: Boolean             = false
    override def isLiteral: Boolean         = true
    override def asBlank: Option[BNode]     = None
    override def asIri: Option[IriNode]     = None
    override def asLiteral: Option[Literal] = Some(this)
    override def toString: String = lexicalForm

    /**
      * @return true if the value is numeric, false otherwise
      */
    def isNumeric: Boolean =
      numericDataTypes.contains(dataType)

    /**
      * @return true if the value is a string or a string tagged with a language, false otherwise
      */
    def isString: Boolean =
      dataType == xsd.string || dataType == rdf.langString

    def isBoolean: Boolean =
      dataType == xsd.boolean
  }

  object Literal {

    final val numericDataTypes: Set[AbsoluteIri] = Set(
      xsd.byte, xsd.short, xsd.int, xsd.integer, xsd.long, xsd.decimal, xsd.double, xsd.float,
      xsd.negativeInteger, xsd.nonNegativeInteger, xsd.nonPositiveInteger, xsd.positiveInteger,
      xsd.unsignedByte, xsd.unsignedShort, xsd.unsignedInt, xsd.unsignedLong)
    

    /**
      * Creates a new Literal node from the arguments.
      *
      * @param lexicalForm the literal lexical form
      * @param dataType    the data type of the literal
      */
    final def apply(lexicalForm: String, dataType: AbsoluteIri): Literal =
      new Literal(lexicalForm, dataType)

    /**
      * Creates a new string literal.  String literals have the ''http://www.w3.org/2001/XMLSchema#string'' data type.
      *
      * @param string the underlying string
      */
    final def apply(string: String): Literal =
      new Literal(string, xsd.string, None)

    /**
      * Creates a new string literal tagged with the argument language tag.  Tagged string literals have the
      * ''http://www.w3.org/1999/02/22-rdf-syntax-ns#langString'' data type.
      *
      * @param string      the underlying string
      * @param languageTag the language tag
      */
    final def apply(string: String, languageTag: LanguageTag): Literal =
      new Literal(string, rdf.langString, Some(languageTag))

    /**
      * Creates a new Boolean literal of ''http://www.w3.org/2001/XMLSchema#boolean'' data type.
      *
      * @param value the underlying boolean value
      */
    final def apply(value: Boolean): Literal =
      new Literal(value.toString, xsd.boolean)

    /**
      * Creates a new Byte literal of ''http://www.w3.org/2001/XMLSchema#byte'' data type.
      *
      * @param value the underlying byte value
      */
    final def apply(value: Byte): Literal =
      new Literal(value.toString, xsd.byte)

    /**
      * Creates a new Integer literal of ''http://www.w3.org/2001/XMLSchema#integer'' data type.
      *
      * @param value the underlying integer value
      */
    final def apply(value: Int): Literal =
      new Literal(value.toString, xsd.integer)

    /**
      * Creates a new Short literal of ''http://www.w3.org/2001/XMLSchema#short'' data type.
      *
      * @param value the underlying short value
      */
    final def apply(value: Short): Literal =
      new Literal(value.toString, xsd.short)

    /**
      * Creates a new Long literal of ''http://www.w3.org/2001/XMLSchema#long'' data type.
      *
      * @param value the underlying long value
      */
    final def apply(value: Long): Literal =
      new Literal(value.toString, xsd.long)

    /**
      * Creates a new Float literal of ''http://www.w3.org/2001/XMLSchema#float'' data type.
      *
      * @param value the underlying float value
      */
    final def apply(value: Float): Literal =
      new Literal(value.toString, xsd.float)

    /**
      * Creates a new Decimal literal of ''http://www.w3.org/2001/XMLSchema#double'' data type.
      *
      * @param value the underlying double value
      */
    final def apply(value: Double): Literal =
      new Literal(value.toString, xsd.double)

    private def escape(c: Char): String = c match {
      case '\\' => "\\\\"
      case '\t' => "\\t"
      case '\r' => "\\r"
      case '\n' => "\\n"
      case '\f' => "\\f"
      case '"'  => "\\\""
      case x    => x.toString
    }

    private def escape(str: String): String =
      str.flatMap(escape(_: Char))

    final implicit def literalShow(implicit is: Show[AbsoluteIri], ls: Show[LanguageTag]): Show[Literal] = Show.show {
      case Literal(f, _, Some(tag))         => s""""${escape(f)}"@${ls.show(tag)}"""
      case l@Literal(f, _, _) if l.isString => s""""${escape(f)}""""
      case Literal(f, dt, None)             => s""""${escape(f)}"^^<${is.show(dt)}>"""
    }

    final implicit val literalEq: Eq[Literal] = Eq.fromUniversalEquals

    /**
      * A language tag as described by BCP 47 (https://tools.ietf.org/html/bcp47#section-2.1).
      *
      * @param value the undelying language tag value
      */
    final case class LanguageTag private[rdf] (value: String)

    object LanguageTag {

      /**
        * Attempts to create a new LanguageTag from the provided string value.  The value must conform to the format
        * specified by BCP 47 (https://tools.ietf.org/html/bcp47#section-2.1).
        *
        * @param string the value to parse as a language tag
        * @return Right(LanguageTag) if the string conforms to the BCP 47 syntax, Left(error) otherwise
        */
      final def apply(string: String): Either[String, LanguageTag] = {
        import org.parboiled2.Parser.DeliveryScheme.Either
        val formatter = new ErrorFormatter(showExpected = false, showTraces = false)
        new LanguageTagParser(string).`Language-Tag`
          .run()
          .map(_ => new LanguageTag(string))
          .leftMap(_.format(string, formatter))
      }

      final implicit val languageTagShow: Show[LanguageTag] = Show.show(_.value)
      final implicit val languageTagEq: Eq[LanguageTag]     = Eq.fromUniversalEquals

      //noinspection TypeAnnotation
      // format: off
      @SuppressWarnings(Array("MethodNames"))
      class LanguageTagParser(val input: ParserInput) extends Parser {
        val singleton = AlphaNum -- "xX"

        def `Language-Tag` = rule {
          (langtag ~ EOI) | (grandfathered ~ EOI) | (privateuse ~ EOI)
        }

        def langtag = rule {
          (
            languageNoExt ~
            ('-' ~ script).? ~
            ('-' ~ region).? ~
            zeroOrMore('-' ~ variant) ~
            zeroOrMore('-' ~ extension) ~
            ('-' ~ privateuse).?
          ) | (
              language ~
              ('-' ~ script).? ~
              ('-' ~ region).? ~
              zeroOrMore('-' ~ variant) ~
              zeroOrMore('-' ~ extension) ~
              ('-' ~ privateuse).?
          )
        }

        def languageNoExt = rule {
          (2 to 3 times Alpha) | (4 to 8 times Alpha)
        }

        def language = rule {
          ((2 to 3 times Alpha) ~ ('-' ~ extlang).?) | (4 to 8 times Alpha)
        }

        def extlang = rule {
          (3 times Alpha) ~ (1 to 2 times ('-' ~ (3 times Alpha))).?
        }

        def script = rule {
          4 times Alpha
        }

        def region = rule {
          (2 times Alpha) | (3 times Digit)
        }

        def variant = rule {
          (5 to 8 times AlphaNum) | (Digit ~ (3 times AlphaNum))
        }

        def extension = rule {
          singleton ~ oneOrMore('-' ~ (2 to 8 times AlphaNum))
        }

        def privateuse = rule {
          'x' ~ oneOrMore('-' ~ (1 to 8 times AlphaNum))
        }

        def grandfathered = rule { irregular | regular }

        def irregular = rule {
          ("en-GB-oed"         // irregular tags do not match
          | "i-ami"             // the 'langtag' production and
          | "i-bnn"             // would not otherwise be
          | "i-default"         // considered 'well-formed'
          | "i-enochian"        // These tags are all valid,
          | "i-hak"             // but most are deprecated
          | "i-klingon"         // in favor of more modern
          | "i-lux"             // subtags or subtag
          | "i-mingo"           // combination
          | "i-navajo"
          | "i-pwn"
          | "i-tao"
          | "i-tay"
          | "i-tsu"
          | "sgn-BE-FR"
          | "sgn-BE-NL"
          | "sgn-CH-DE")
        }

        def regular = rule {
          ( "art-lojban"       // these tags match the 'langtag'
          | "cel-gaulish"      // production, but their subtags
          | "no-bok"           // are not extended language
          | "no-nyn"           // or variant subtags: their meaning
          | "zh-guoyu"         // is defined by their registration
          | "zh-hakka"         // and all of these are deprecated
          | "zh-min"           // in favor of a more modern
          | "zh-min-nan"       // subtag or sequence of subtags
          | "zh-xiang")
        }
      }
      // format: on
    }
  }

  implicit final def nodeShow(implicit I: Show[IriNode], B: Show[BNode], L: Show[Literal]): Show[Node] = Show.show {
    case v: IriNode => I.show(v)
    case v: BNode   => B.show(v)
    case v: Literal => L.show(v)
  }
  implicit final def nodeEq: Eq[Node] = Eq.fromUniversalEquals
}

trait PrimitiveNodeConversions {

  implicit final def byteToNode(value: Byte): Node       = Literal(value)
  implicit final def shortToNode(value: Short): Node     = Literal(value)
  implicit final def intToNode(value: Int): Node         = Literal(value)
  implicit final def longToNode(value: Long): Node       = Literal(value)
  implicit final def floatToNode(value: Float): Node     = Literal(value)
  implicit final def doubleToNode(value: Double): Node   = Literal(value)
  implicit final def booleanToNode(value: Boolean): Node = Literal(value)
  implicit final def stringToNode(value: String): Node   = Literal(value)

}

trait StandardNodeConversions {
  implicit final def uuidToNode(value: UUID): Node = Literal(value.toString)
}

trait FunctionConversions {
  implicit final def nodeToPredicate(node: Node): Node => Boolean      = _ == node
  implicit final def iriToPredicate(iri: AbsoluteIri): Node => Boolean = _ == IriNode(iri)
}

trait RdfConversions {
  implicit final def nodeFromAbsoluteOri(iri: AbsoluteIri): IriNode = IriNode(iri)
}
