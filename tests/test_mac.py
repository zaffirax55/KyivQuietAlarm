import sys
import unittest
from pathlib import Path
from unittest.mock import patch, Mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
import agent

class MacDiscoveryTests(unittest.TestCase):
    def test_returns_none_outside_windows(self):
        with patch.object(agent.os, 'name', 'posix'):
            self.assertEqual(agent.available_macs(), [])

    @unittest.skipUnless(sys.platform == 'win32', 'Windows getmac command only')
    def test_parse_getmac_csv(self):
        text='"Connection Name","Network Adapter","Physical Address","Transport Name"\n"Wi-Fi","Realtek 8821CE","11-22-33-44-55-66","\\\\Device\\Tcpip_X"\n'
        with patch.object(agent.subprocess, 'run', return_value=Mock(stdout=text.encode('ascii'))):
            self.assertIn(('Realtek 8821CE', '11:22:33:44:55:66'), agent.available_macs())

if __name__ == '__main__':
    unittest.main()
