// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.app

import java.io.File
import java.time.{Instant, LocalDate}

import com.daml.ledger.javaapi.data.{CreateCommand, Party, Record}
import com.digitalasset.app.integration.MarketSetup
import com.google.gson.JsonObject
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConverters._
import scala.io.Source

object Commands {
  var host = ""
  var port = 0

  // Config
  private val config = ConfigFactory.load()
  private val parties = config.getStringList("parties").asScala.toList
  private val dataProvider = config.getStringList("dataProvider").asScala.toList
  private val centralBanks = config.getStringList("centralBanks").asScala.toList

  // Clients
  private lazy val client = initClient()
  private lazy val schema = client.loadSchemas(config.getStringList("typeModules").asScala.toList)
  private lazy val party2dataLoading =
    (dataProvider ++ centralBanks ++ parties)
      .map(p => (p, new integration.DataLoading(p, client, schema)))
      .toMap
  private lazy val party2derivedEvents =
    parties
      .map(p => (p, new integration.DerivedEvents(p, client)))
      .toMap

  private def initClient(): LedgerClient = {
    new LedgerClient(
      Config(
        config.getString("id"),
        host,
        port,
        config.getInt("platform.maxRecordOffset"),
        config.getBoolean("platform.useStaticTime")
      )
    )
  }

  def init() : Unit = {
    // Evaluate lazy variables
    party2dataLoading.map{case (x, _) => x}
    party2derivedEvents.map{case (x, _) => x}
  }

  def getTime(): Instant = {
    client.getTime()
  }

  // Data loading
  def initMarket(directory: String, time: String = ""): Unit = {
    if (time != "") client.setTime(Instant.parse(time))
    parties.foreach(createAllocateWorfklow)
    parties.foreach(createDeriveEventsWorkflow)
    loadMasterAgreements(directory + "/MasterAgreement.csv")
    loadHolidayCalendars(directory + "/HolidayCalendar.csv")
    loadCash(directory + "/Cash.csv")
    parties.foreach(p => new MarketSetup(p, client).run())
  }

  private def createAllocateWorfklow(party: String): Unit = {
    val arg = new Record(List(
      new Record.Field("sig", new Party(party))
    ).asJava)
    val cmd = new CreateCommand(client.getTemplateId("AllocateWorkflow"), arg)
    client.sendCommands(party, List(cmd))
  }

  private def createDeriveEventsWorkflow(party: String): Unit = {
    val arg = new Record(List(
      new Record.Field("sig", new Party(party))
    ).asJava)
    val cmd = new CreateCommand(client.getTemplateId("DeriveEventsWorkflow"), arg)
    client.sendCommands(party, List(cmd))
  }



  def publishRateFixing(publisher: String, date: String, rateIndex: String, tenor: String, value: Double): Unit = {
    party2dataLoading(publisher).loadRateFixing(date, rateIndex, tenor, value, parties)
  }

  def publishRateFixingSingleParty(publisher: String, date: String, rateIndex: String, tenor: String, value: String, party: String): Unit = {
    party2dataLoading(publisher).loadRateFixing(date, rateIndex, tenor, value.toDouble, List(party))
  }

  def publishRateFixings(file: String): Unit = {
    val bufferedSource = Source.fromFile(file)
    for (line <- bufferedSource.getLines) {
      val cols = line.split(",").map(_.trim)
      party2dataLoading(cols(1)).loadRateFixing(cols(0), cols(2), cols(3), cols(4).toDouble, parties)
    }
    bufferedSource.close
  }

  def loadMasterAgreements(file: String): Unit = {
    val bufferedSource = Source.fromFile(file)
    for (line <- bufferedSource.getLines.drop(1)) {
      val cols = line.split(",").map(_.trim)
      party2dataLoading(cols(0)).loadMasterAgreement(cols(1))
    }
    bufferedSource.close
  }

  def loadHolidayCalendars(file: String): Unit = {
    val bufferedSource = Source.fromFile(file)
    for (line <- bufferedSource.getLines.drop(1)) {
      val cols = line.split(",").map(_.trim)
      party2dataLoading(cols(0)).loadHolidayCalendar(cols(1), cols(2).split(";").toList, parties)
    }
    bufferedSource.close
  }

  def loadCash(file: String): Unit = {
    val bufferedSource = Source.fromFile(file)
    for (line <- bufferedSource.getLines.drop(1)) {
      val cols = line.split(",").map(_.trim)
      party2dataLoading(cols(0)).loadCash(cols(1), cols(2), cols(3).toDouble, cols(4))
    }
    bufferedSource.close
  }

  def loadEvents(directory: String): Unit = {
    utils.Json.loadJsons(directory).foreach(loadEventFromJson)
  }

  def loadEvent(file: String): Unit = {
    utils.Json.loadJson(new File(file)).foreach(loadEventFromJson)
  }

  private def loadEventFromJson(json: JsonObject): Unit = {
    val party = json.getAsJsonObject("argument").getAsJsonArray("ps").iterator.next.getAsJsonObject.get("p").getAsString
    party2dataLoading(party).loadEvent(json)
  }

  def deriveEvents(party: String, contractRosettaKey: String): Unit = {
    party2derivedEvents(party).deriveEvents(contractRosettaKey, None, None)
  }

  def deriveEventsAll(party: String, fromDate: Option[String], toDate: Option[String]): Unit = {
    party2derivedEvents(party).deriveEventsAll(fromDate.map(LocalDate.parse), toDate.map(LocalDate.parse))
  }

  def createNextDerivedEvent(party: String, contractRosettaKey: String, eventQualifier: String): Unit = {
    party2derivedEvents(party).createNextDerivedEvent(contractRosettaKey, eventQualifier)
  }

  def removeDerivedEvents(party: String, contractRosettaKey: String): Unit = {
    party2derivedEvents(party).removeDerivedEvents(contractRosettaKey)
  }
}
