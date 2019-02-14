#!/usr/bin/python

'''
    This script prints hostlists of our datacenter.
    To be used (not only) by:
    * Ansible as a dynamic inventory
    * the checkConnectivity utility scripts

    At some point in the future we might to merge this Inventory with the Monitoring Inventory:
'''

import argparse
import os
import re
import json
import socket
