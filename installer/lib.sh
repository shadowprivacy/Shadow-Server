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

function check_app
{
    which "$1" > /dev/null 2> /dev/null
    rs=$?
    if [ $rs -eq 1 ]; 
    then
        echo 1
    else 
        echo 0
    fi
}

function download_sticker_pack
{
    PACK_ID=$1
    PACK_MAX_ID=$2
    PACK_URL=$3

    mkdir -p ${DATA_PATH}/stickers/$PACK_ID/full

    cd ${DATA_PATH}/stickers/$PACK_ID

    wget https://$PACK_URL/stickers/$PACK_ID/manifest.proto --no-check-certificate

    cd ${DATA_PATH}/stickers/$PACK_ID/full

    for i in $(seq 0 $PACK_MAX_ID)
       do
          wget https://$PACK_URL/stickers/$PACK_ID/full/$i --no-check-certificate
       done
}