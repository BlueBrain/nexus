package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.MigrateCompositeViews._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, Tag}
import doobie.implicits._
import fs2.Stream
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.Task

/**
  * Migration of composite views to inject index rev with the current rev value
  */
final class MigrateCompositeViews(xas: Transactors) {

  def run: Task[(Int, Int)] = {
    for {
      _              <- Stream.eval(logger.info("Starting composite views migration"))
      migratedEvents <- migrateEvents
      migratedStates <- migrateStates
    } yield (migratedEvents, migratedStates)
  }.compile.last.map(_.getOrElse((0, 0))).tapEval { count =>
    logger.info(s"Composite views migration is now complete with $count events/states updated")
  }

  private def migrateEvents = Stream
    .unfoldEval(0) { count =>
      eventsToMigrate(xas)
        .flatMap {
          _.traverse { case (org, project, id, rev, value) =>
            val migrated = injectRev(value, rev)
            updateEvent(org, project, id, rev, migrated)
          }.transact(xas.write)
        }
        .map { list =>
          Option.when(list.nonEmpty)((count + list.size, count + list.size))
        }
    }
    .evalTap { count =>
      logger.info(s"'$count' composite views events have been migrated")
    }

  private def migrateStates = Stream
    .unfoldEval(0) { count =>
      statesToMigrate(xas)
        .flatMap {
          _.traverse { case (org, project, id, tag, rev, value) =>
            val migrated = injectRev(value, rev)
            updateState(org, project, id, tag, migrated)
          }.transact(xas.write)
        }
        .map { list =>
          Option.when(list.nonEmpty)((count + list.size, count + list.size))
        }
    }
    .evalTap { count =>
      logger.info(s"'$count' composite views states have been migrated")
    }

}

object MigrateCompositeViews {

  private val logger: Logger = Logger[MigrateCompositeViews]

  private[migration] def eventsToMigrate(xas: Transactors) =
    sql"""SELECT org, project, id, rev, value
         |FROM public.scoped_events
         |WHERE type = ${CompositeViews.entityType}
         |AND (value ->> '@type' = 'CompositeViewCreated' OR value ->> '@type' = 'CompositeViewUpdated')
         |AND value -> 'value' ->> 'sourceIndexingRev' is null
         |LIMIT 500""".stripMargin
      .query[(Label, Label, Iri, Int, Json)]
      .to[List]
      .transact(xas.read)

  private[migration] def updateEvent(org: Label, project: Label, id: Iri, rev: Int, value: Json) =
    sql"""
         | UPDATE public.scoped_events SET
         |  value = $value
         | WHERE
         |  org = $org AND
         |  project = $project AND
         |  id =  $id AND
         |  rev = $rev""".stripMargin.update.run.void

  private[migration] def injectRev(json: Json, rev: Int) =
    json.hcursor
      .downField("value")
      .withFocus {
        _.mapObject { value =>
          val projections        = value("projections").flatMap(_.asArray).getOrElse(Vector.empty)
          val updatedProjections = projections.flatMap(_.asObject.map(_.add("indexingRev", rev.asJson)))
          value.add("projections", updatedProjections.asJson).add("sourceIndexingRev", rev.asJson)
        }
      }
      .top
      .getOrElse(throw new IllegalStateException(s"Json $json could not be processed."))

  private[migration] def statesToMigrate(xas: Transactors) =
    sql"""SELECT org, project, id, tag, rev, value
         |FROM public.scoped_states
         |WHERE type = ${CompositeViews.entityType}
         |AND value -> 'value' ->> 'sourceIndexingRev' is null
         |LIMIT 500""".stripMargin
      .query[(Label, Label, Iri, Tag, Int, Json)]
      .to[List]
      .transact(xas.read)

  private[migration] def updateState(org: Label, project: Label, id: Iri, tag: Tag, value: Json) =
    sql"""
         | UPDATE public.scoped_states SET
         |  value = $value
         | WHERE
         |  org = $org AND
         |  project = $project AND
         |  id = $id AND
         |  tag = $tag""".stripMargin.update.run.void

}
