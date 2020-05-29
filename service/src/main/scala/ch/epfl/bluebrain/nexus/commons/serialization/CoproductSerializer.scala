package ch.epfl.bluebrain.nexus.commons.serialization

import java.nio.charset.StandardCharsets.UTF_8

import io.circe.parser.decode
import io.circe.{Decoder, Encoder, Printer}
import shapeless._

/**
  * Shapeless coproduct based serializer that describes functions for serializing values to byte arrays using
  * manifests.  Values are serialized only if they compatible with the types described by the coproduct.  When values
  * are not compatible with the types described by the coproduct the ''manifest'' and ''toBinary'' functions return
  * no value.
  *
  * Deserialization occurs by looking up the the provided manifest in the known manifests in order and attempting to
  * apply the appropriate deserialization.
  *
  * @tparam C the coproduct type
  */
trait CoproductSerializer[C <: Coproduct] {

  /**
    * Attempts to compute a string manifest for the provided value ''x''.
    *
    * @param x the value for which the manifest is computed
    * @return Some(manifest) if the operation succeeds, None otherwise
    */
  def manifest(x: Any): Option[String]

  /**
    * Attempts to serialize the argument ''x'' value to an array of bytes.
    *
    * @param x the value to serialize
    * @return Some(Array(byte)) if the serialization succeeds, None otherwise
    */
  def toBinary(x: Any): Option[Array[Byte]]

  /**
    * Attempts to deserialize the provided ''bytes'' into a value of one of the types of the coproduct ''C'' using the
    * argument ''manifest'' as the selector for the appropriate outcome type.
    *
    * @param bytes    the array of bytes to deserialize
    * @param manifest the manifest to be used for selecting the appropriate resulting type
    * @return an optional instance of the ''C'' coproduct
    */
  def fromBinary(bytes: Array[Byte], manifest: String): Option[C]
}

object CoproductSerializer {

  private val printer = Printer.noSpaces.copy(dropNullValues = true)

  /**
    * Summons a serializer instance from the implicit scope.
    *
    * @param instance the implicitly available instance
    * @tparam C the generic coproduct type of the serializer
    * @return the implicitly available serializer instance
    */
  def apply[C <: Coproduct](implicit instance: CoproductSerializer[C]): CoproductSerializer[C] = instance

  /**
    * Base recursion case for deriving serializers.
    */
  final implicit val cnilSerializer: CoproductSerializer[CNil] = new CoproductSerializer[CNil] {
    override def manifest(x: Any): Option[String]                               = None
    override def toBinary(x: Any): Option[Array[Byte]]                          = None
    override def fromBinary(bytes: Array[Byte], manifest: String): Option[CNil] = None
  }

  /**
    * Inductive case for deriving ''CoproductSerializer'' instances using implicitly available typeclasses.  The derived
    * serializer uses json encoders and decoders to covert values to and from byte arrays.
    *
    * @param headTypeable   a typeable typeclass instance for the head ''H'' type
    * @param headEncoder    an encoder instance for the head ''H'' type
    * @param headDecoder    a decoder instance for the head ''H'' type
    * @param tailSerializer a ''CoproductSerializer'' for the tail ''T'' type
    * @tparam H the type of the coproduct head
    * @tparam T the type of the coproduct tail
    * @return a new ''CoproductSerializer'' that prepends a new type ''H'' to the list of known serialization types
    */
  final implicit def cconsSerializer[H, T <: Coproduct](
      implicit
      headTypeable: Typeable[H],
      headEncoder: Encoder[H],
      headDecoder: Decoder[H],
      tailSerializer: CoproductSerializer[T]
  ): CoproductSerializer[H :+: T] = new CoproductSerializer[:+:[H, T]] {

    override def manifest(x: Any): Option[String] =
      headTypeable
        .cast(x)
        .map(_ => headTypeable.describe)
        .orElse(tailSerializer.manifest(x))

    override def toBinary(x: Any): Option[Array[Byte]] =
      headTypeable
        .cast(x)
        .map(h => headEncoder(h).printWith(printer).getBytes(UTF_8))
        .orElse(tailSerializer.toBinary(x))

    override def fromBinary(bytes: Array[Byte], manifest: String): Option[H :+: T] =
      if (headTypeable.describe == manifest) {
        decode[H](new String(bytes, UTF_8)) match {
          case Left(_)      => None
          case Right(value) => Some(Coproduct(value))
        }
      } else tailSerializer.fromBinary(bytes, manifest).map(t => t.extendLeft[H])
  }

}
