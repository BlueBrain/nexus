package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.serialization

import akka.actor.ExtendedActorSystem
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.serialization.KryoSerializerInit.OrderingViewRefSerializer
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo

class KryoSerializerInit extends DefaultKryoInitializer {

  override def postInit(kryo: ScalaKryo, system: ExtendedActorSystem): Unit = {
    super.postInit(kryo, system)
    kryo.addDefaultSerializer(classOf[Ordering[ViewRef]], classOf[OrderingViewRefSerializer])
    kryo.register(classOf[Ordering[ViewRef]], new OrderingViewRefSerializer)
    ()
  }
}

object KryoSerializerInit {

  class OrderingViewRefSerializer extends Serializer[Ordering[ViewRef]] {
    override def write(kryo: Kryo, output: Output, `object`: Ordering[ViewRef]): Unit = ()

    override def read(kryo: Kryo, input: Input, `type`: Class[_ <: Ordering[ViewRef]]): Ordering[ViewRef] =
      implicitly[Ordering[ViewRef]]
  }

}
