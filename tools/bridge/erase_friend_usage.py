"""Account-deletion tail invoked locally under the server's account-generation lock."""
from pathlib import Path
import sys

from api_budget import CreditLedger

if __name__ == "__main__":
    state, config, friend = sys.argv[1:]
    CreditLedger(Path(state)).erase_friend(friend, Path(config))
