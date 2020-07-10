#!/bin/sh

cd ./service/protobuf
make
cd -

mvn -e clean package 
