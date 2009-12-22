import javax.persistence.Id;
import javax.persistence.Entity;

@Entity
public class PersistentVersionnedObject {

	private long id;
	private long version;
	private String champString;
	private Long champLong;
	
	@Id
	public long getId() {
		return id;
	}
	public void setId(long id) {
		this.id = id;
	}
	@Version
	public long getVersion() {
		return version;
	}
	public void setVersion(long version) {
		this.version = version;
	}
	public String getChampString() {
		return champString;
	}	
	public void setChampString(String a) {
		this.champString = a;
	}
	public Long getChampLong() {
		return champLong;
	}
	public void setChampLong(Long a) {
		this.champLong = a;
	}
}
