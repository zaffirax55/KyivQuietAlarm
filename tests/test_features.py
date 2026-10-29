import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'windows'))
import agent

class FeatureTests(unittest.TestCase):
    def test_media_keys_have_exact_vk(self):
        with patch.object(agent,'media_key') as fn:
            for cmd,code in [('media_play_pause',0xB3),('media_next',0xB0),
                             ('media_prev',0xB1),('slide_next',0x22),
                             ('slide_prev',0x21),('presentation_start',0x74),
                             ('presentation_end',0x1B)]:
                fn.reset_mock()
                self.assertTrue(agent.execute(cmd))
                fn.assert_called_once_with(code)

    def test_unrecognized_command_refused(self):
        for cmd in ('shell','cmd.exe','format','open_url','start_process'):
            with self.assertRaises(ValueError):
                agent.execute(cmd)

    def test_status_lists_only_public_adapter_info(self):
        with patch.object(agent.State,'cached_macs',[('Ethernet','AA:BB:CC:DD:EE:FF')]):
            with patch.object(agent.State,'wake_armed_devices',['Ethernet adapter']):
                data=agent.info()
                self.assertEqual(data['adapters'][0]['mac'],'AA:BB:CC:DD:EE:FF')
                self.assertEqual(data['wake_armed_devices'],['Ethernet adapter'])
                self.assertNotIn('secret',data)

    def test_power_diagnostics_is_read_only_on_non_windows(self):
        if sys.platform != 'win32':
            self.assertEqual(agent.wake_capable_devices(),[])

if __name__=='__main__':
    unittest.main()
