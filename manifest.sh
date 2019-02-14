#!/bin/bash

source ~/.bash_profile

#type ruby >/dev/null 2>&1 || { echo >&2 "I require ruby but it's not installed.  Aborting."; exit 1;}

#cd /etc/puppetlabs/code/environments/CSESRE2087/pips_modules/pips_web/ || exit
cd /etc/puppetlabs/code/environments/production/ || exit
#cd /tmp/role || exit
find . -name "*.pp" | while read -r val; do puppet parser validate "$val"; done
