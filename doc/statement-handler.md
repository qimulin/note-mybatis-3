# StatementHandler
## 定义
JDBC处理器，基于JDBC构建Statement并设置参数，然后执行Sql，每调用会话当中一次Sql，都会有与之对应的且唯一的Statement实例。
## 代码结构
首先有个[StatementHandler](../src/main/java/org/apache/ibatis/executor/statement/StatementHandler.java)接口，主要提供的方法有：
- 声明Statement（创建、预处理Statement，总之都是调用JDBC的Connection里API里的方法）
- 设置参数
- 查询
- 修改

StatementHandler有三个实现，SimpleStatementHandler（简单处理器）、PreparedStatementHandler（预处理器）、
CallableStatementHandler（存储过程处理器）。而和Executor很像的是StatementHandler和上述三者之间有一个抽象类的
实现[BaseStatementHandler](../src/main/java/org/apache/ibatis/executor/statement/BaseStatementHandler.java)，以处理他们公共的逻辑。
Statement（爷爷）——>PrepareStatement（父亲）——>CallableStatement（我），都是一个继承的关系。在实际使用上，
百分之九十九都是使用的PrepareStatementHandler，因为用预处理的Statement性能更高，而且更安全（比如它里面会对Sql参数进行转义，有效防止Sql注入）。


