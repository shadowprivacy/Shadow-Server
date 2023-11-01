package su.sres.shadowserver.storage;

import java.util.Optional;
import java.util.UUID;

public interface AccountStore {

  boolean create(Account account, long directoryVersion);

  void update(Account account) throws ContestedOptimisticLockException;
    
  Optional<Account> get(String userLogin);

  Optional<Account> get(UUID uuid);

  void delete(final UUID uuid, long directoryVersion);
}
