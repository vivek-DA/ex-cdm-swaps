#!/usr/bin/env bash

run_script() {
  local ledgerHost="localhost"
  local ledgerPort="6865"
  local darFile=".daml/dist/CdmSwaps-1.0.0.dar"
  local script_name="$1"
  daml script \
    --dar "${darFile}" \
    --ledger-host "${ledgerHost}" \
    --ledger-port "${ledgerPort}" \
    --script-name "$script_name" \
    --static-time
}

run_script "$1"
