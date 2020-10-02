package ch.epfl.bluebrain.delta.testplugin

import cats.effect.concurrent.Ref
import monix.bio.Task

class KVStore(ref: Ref[Task, Map[String, String]]) {

  def get(key: String): Task[Option[String]] = ref.get.map(_.get(key))

  def test() =  println("test from classloader")
}