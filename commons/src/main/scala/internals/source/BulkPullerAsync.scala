package com.mfglabs.stream.internals.source

import akka.pattern.pipe
import akka.actor.{Props, Status, ActorLogging}
import akka.stream.actor.ActorPublisher

import scala.concurrent.Future

class BulkPullerAsync[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]) extends ActorPublisher[A] with ActorLogging {
  import akka.stream.actor.ActorPublisherMessage._
  implicit val ec = context.dispatcher

  def receive = waitingForDownstreamReq(offset)

  case object Pull

  def waitingForDownstreamReq(s: Long): Receive = {
    case Request(_) | Pull =>
      if (totalDemand > 0 && isActive) {
        f(s, totalDemand.toInt).pipeTo(self)
        context.become(waitingForFut(s, totalDemand))
      }

    case Cancel => context.stop(self)
  }

  def waitingForFut(s: Long, beforeFutDemand: Long): Receive = {
    case (as: Seq[A], stop: Boolean) =>
      val (requestedAs, unwantedAs) = as.splitAt(beforeFutDemand.toInt)
      requestedAs.foreach(onNext)
      if (unwantedAs.nonEmpty) {
        log.warning(s"Requested $beforeFutDemand elements, but received too many elements. Ignoring ${unwantedAs.size} unwanted element(s).")
      }
      if (stop) {
        onComplete()
      } else {
        if (totalDemand > 0) self ! Pull
        context.become(waitingForDownstreamReq(s + requestedAs.length))
      }

    case Request(_) | Pull => // ignoring until we receive the future response

    case Status.Failure(err) =>
      context.become(waitingForDownstreamReq(s))
      onError(err)

    case Cancel => context.stop(self)
  }

}

object BulkPullerAsync {
  def props[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]) = Props(new BulkPullerAsync[A](offset)(f))
}