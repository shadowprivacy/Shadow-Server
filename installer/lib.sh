#!/bin/bash

USER_SH="shadow"
USER_PATH="/home/${USER_SH}"

SERVER_PATH="${USER_PATH}/shadowserver"
DATA_PATH="${USER_PATH}/data"
MINIO_PATH="${USER_PATH}/minio"

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
     echo $(sed "s/\&/\\\&/" <<< "$INT")
}

function normalize_turn

{      
     echo $(sed "s/\&/\\\&/" <<< "$1")
}


function preproc_cfg
{        
    echo $(sed 's/\\/\\\\/g' <<< "$1")
}