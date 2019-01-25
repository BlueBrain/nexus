
# Linking data on the web

## Overview

* Prerequisites
    * MovieLens dataset is already stored in Nexus
    * MovieLens dataset visible in Nexus Web
    * WikiData Sparql service up
    * Neuxs Sparql service up

* Step 1: Link with external sources
    * For every movie get the logo, US publication date, lang and store it back into Nexus:

```
   SELECT *
   WHERE
   {
   	?movie wdt:P4947 "862".
       OPTIONAL{
       ?movie wdt:P154 ?logo.
       }
       OPTIONAL{
       ?movie wdt:P364 ?lang.
       }
     OPTIONAL{
       ?movie wdt:P577 ?releaseDate.
       ?movie wdt:P291 wdt:Q30.
       }
   }
```
    * show screencasts from Nexus Web

Step 2: tag the new dataset

## What you'll build
In this tutorial, you'll build a simple pipeline


## What you'll learn

* Experience how to join data in Blue Brain Nexus with external structured data on the web (e.g. Wikidata)

## What you'll need


## Get the tutorial code