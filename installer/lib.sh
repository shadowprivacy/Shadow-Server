#!/bin/bash

SHADOW_SERVER_VERSION=2.7.0

USER_SH="shadow"
USER_PATH="/home/${USER_SH}"

SERVER_PATH="${USER_PATH}/shadowserver"
DATA_PATH="${USER_PATH}/data"
MINIO_PATH="${USER_PATH}/minio"
SFU_PATH="${USER_PATH}/sfu"

function normalize_bash
{        
    echo $(sed 's/./\\&/g' <<< "$1")
}

function normalize_sql
{        
    echo $(sed "s/'/''/g" <<< "$1")
}

function normalize_yaml

{        
     INT=$(normalize_sql $1)
     echo $(sed "s/\&/\\\&/g" <<< "$INT")
}

function normalize_turn

{      
     echo $(sed "s/\&/\\\&/g" <<< "$1")
}


function preproc_cfg
{        
    echo $(sed 's/\\/\\\\/g' <<< "$1")
}

function preproc_sed
{        
    echo $(sed 's|/|\\/|g' <<< "$1")
}

function extract_tld
{        
    echo $(grep -o '[^.]*$' <<< "$1")
}

function download_sticker_pack
{
    PACK_ID=$1
    PACK_MAX_ID=$2

    mkdir -p ${DATA_PATH}/stickers/$PACK_ID/full

    cd ${DATA_PATH}/stickers/$PACK_ID

    wget https://cdn.signal.org/stickers/$PACK_ID/manifest.proto --no-check-certificate

    cd ${DATA_PATH}/stickers/$PACK_ID/full

    for i in $(seq 0 $PACK_MAX_ID)
       do
          wget https://cdn.signal.org/stickers/$PACK_ID/full/$i --no-check-certificate
       done
}