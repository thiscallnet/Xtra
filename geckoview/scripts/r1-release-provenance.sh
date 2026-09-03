#!/usr/bin/env bash

r1_release_qualified_commit() {
  jq -er '
    if (.qualifiedCommit | type) == "string" then .qualifiedCommit
    elif (.xtraCommit | type) == "string" then .xtraCommit
    else error("release manifest has no qualified commit") end
  ' "$1"
}

r1_release_publication_commit() {
  jq -er '
    if (.publicationCommit | type) == "string" then .publicationCommit
    elif (.xtraCommit | type) == "string" then .xtraCommit
    else error("release manifest has no publication commit") end
  ' "$1"
}
