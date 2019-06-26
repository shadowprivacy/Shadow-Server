#!/bin/sh

cd ./service/protobuf
make
cd -

mvn -X -e clean package 
