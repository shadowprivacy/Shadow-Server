#!/bin/bash

. ./lib.sh

function check_root_and_exit
{
    if [ $(id -u) -ne 0 ] 
    then 
        echo "This script must be run under sudo"
        exit 1
    fi
}

check_root_and_exit

set +o history

read -p "This will upgrade your Shadow server installation to the version ${SHADOW_SERVER_VERSION}. Are you sure that:
- your current version of Shadow server is 2.4.0 
- the Shadow server is stopped
- the new jar file is in place
- you have no important pending accounts (their information will not be preserved during migration)
- the configuration file is upgraded
- the configuration check command reports no errors? [y/n] >> " -n 1 -r
if ! [[ $REPLY =~ ^[Yy]$ ]]
then
    echo " ...Exiting"
    exit 1
fi

sed -i "s/SHADOW_VER/${SHADOW_SERVER_VERSION}/" shadow.service
sed -i "s|/home/shadow/shadowserver|${SERVER_PATH}|" shadow.service
sed -i "s/=shadow/=${USER_SH}/" shadow.service
sed -i "s/shadow bash/${USER_SH} bash/" shadow.service
cp -f shadow.service /etc/systemd/system/

systemctl daemon-reload

cd ${SERVER_PATH}/

printf "\nPerforming PostgreSQL accounts db markup..."

java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar accountdb migrate ${SERVER_PATH}/config/shadow.yml
    
printf "\nPerforming ScyllaDB table markup..."
    
java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar createdeletedaccountsdb ${SERVER_PATH}/config/shadow.yml
java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar creatependingsdb ${SERVER_PATH}/config/shadow.yml

printf "\nDropping obsolete ScyllaDB tables..."

printf "\nPlease enter your password for the ScyllaDB superuser 'cassandra' (press Enter to use the default)>>"
read -r CASSANDRA_PASSWORD 

if [ -z "$CASSANDRA_PASSWORD" ]
    then 
    CASSANDRA_PASSWORD="cassandra"
fi
   
echo "DROP KEYSPACE alternator_migrationretrydb;" > scyllatext
echo "DROP KEYSPACE alternator_migrationdeleteddb;" >> scyllatext
cqlsh -u cassandra -p $CASSANDRA_PASSWORD -f scyllatext        

# Cleanup

rm -f scyllatext 

printf "\nUpgrade complete. Follow the release notes to perform necessary post-installation tasks (if any), then start the Shadow server: 'systemctl start shadow'.\n"

set -o history
